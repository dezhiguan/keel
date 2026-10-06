package com.keel.server.insight;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.registry.model.dto.AgentSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 列表卡片上的三项指标。24 小时调用按 trace 去重：本机探针和 Langfuse 根节点对上同一个 trace 只算一次。
 * 成本用薄网关按上海时区日切的 spentCny，和日预算同一口径。评测分只读一份实验汇总，读不到再用 Langfuse scores。
 * 累计调用和环比用本机 trace 记录，不翻 Langfuse 全量历史。读不到的来源保持 null，不把故障写成 0。
 * 结果缓存几分钟；过期后先返回上一份，后台再刷新。
 */
@Service
public class AgentUsageService {
    private static final Duration TTL = Duration.ofMinutes(3);
    private static final Instant EPOCH = Instant.parse("2020-01-01T00:00:00Z");
    private final LangfuseClient langfuse;
    private final LiteLlmClient gateway;
    private final QualityService quality;
    private final EvalQueryService evaluations;
    private final SavedTraces saved;
    private final Clock clock;
    private final ExecutorService parallel;
    private final Executor refresh;
    private final Map<String, Snapshot> cache = new HashMap<>();
    private final Map<String, Instant> cachedAt = new HashMap<>();
    private final Set<String> refreshing = new HashSet<>();
    private final ConcurrentHashMap<String, CompletableFuture<Snapshot>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public AgentUsageService(LangfuseClient langfuse, LiteLlmClient gateway, QualityService quality,
                             EvalQueryService evaluations, SavedTraces saved) {
        this(langfuse, gateway, quality, evaluations, saved, Clock.systemUTC());
    }

    AgentUsageService(LangfuseClient langfuse, LiteLlmClient gateway, QualityService quality,
                      EvalQueryService evaluations, SavedTraces saved, Clock clock) {
        this(langfuse, gateway, quality, evaluations, saved, clock,
                Executors.newVirtualThreadPerTaskExecutor(), Executors.newVirtualThreadPerTaskExecutor());
    }

    AgentUsageService(LangfuseClient langfuse, LiteLlmClient gateway, QualityService quality,
                      EvalQueryService evaluations, SavedTraces saved, Clock clock,
                      ExecutorService parallel, Executor refresh) {
        this.langfuse = langfuse;
        this.gateway = gateway;
        this.quality = quality;
        this.evaluations = evaluations;
        this.saved = saved == null ? SavedTraces.EMPTY : saved;
        this.clock = clock;
        this.parallel = parallel;
        this.refresh = refresh;
    }

    public List<AgentSummary> apply(List<AgentSummary> items, String env) {
        var snap = snapshot(env == null || env.isBlank() ? "all" : env);
        return items.stream().map(item -> item.withUsage(
                snap.callsKnown ? snap.calls.getOrDefault(item.name(), 0L) : null,
                snap.totalsKnown ? snap.totals.getOrDefault(item.name(), 0L) : null,
                snap.p95.get(item.name()),
                snap.costKnown ? snap.cost.getOrDefault(item.name(), 0.0) : null,
                snap.budgets.get(item.name()),
                snap.scores.get(item.name()))).toList();
    }

    public Snapshot snapshotOf(String env) {
        return snapshot(env == null || env.isBlank() ? "all" : env);
    }

    public AgentSummary apply(AgentSummary summary, String env) {
        return apply(List.of(summary), env).getFirst();
    }

    private Snapshot snapshot(String env) {
        synchronized (this) {
            var ready = freshOrStale(env, clock.instant());
            if (ready != null) {
                return ready;
            }
        }
        // Load outside the monitor. Blocking on Langfuse while holding synchronized
        // pins the virtual thread and stalls every other request on a one-CPU pod.
        var created = new CompletableFuture<Snapshot>();
        var existing = inflight.putIfAbsent(env, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            var loaded = load(env, clock.instant());
            synchronized (this) {
                cache.put(env, loaded);
                cachedAt.put(env, clock.instant());
            }
            created.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(env, created);
        }
    }

    /** Caller holds {@code this}. Returns a fresh snapshot, or the previous one while a refresh is scheduled. */
    private Snapshot freshOrStale(String env, Instant now) {
        var hit = cache.get(env);
        var at = cachedAt.get(env);
        if (hit == null || at == null) {
            return null;
        }
        if (at.plus(TTL).isAfter(now)) {
            return hit;
        }
        scheduleRefresh(env);
        return hit;
    }

    /** Caller holds {@code this}. */
    private void scheduleRefresh(String env) {
        if (!refreshing.add(env)) {
            return;
        }
        refresh.execute(() -> {
            try {
                var loaded = load(env, clock.instant());
                synchronized (AgentUsageService.this) {
                    cache.put(env, loaded);
                    cachedAt.put(env, clock.instant());
                }
            } finally {
                synchronized (AgentUsageService.this) {
                    refreshing.remove(env);
                }
            }
        });
    }

    private Snapshot load(String env, Instant now) {
        var since = now.minus(Duration.ofHours(24));
        var prevFrom = now.minus(Duration.ofHours(48));
        var rootsF = parallel.submit(() -> langfuse.roots(since, now));
        var keysF = parallel.submit(this::loadKeys);
        var qualityF = parallel.submit(this::qualityScores);
        var experimentF = parallel.submit(this::experimentScores);
        var recentF = parallel.submit(() -> localHits(since, env));
        var spanF = parallel.submit(() -> localHits(prevFrom, env));
        var allF = parallel.submit(() -> localHits(EPOCH, env));

        var traces = new LinkedHashMap<String, String>();
        var latency = new HashMap<String, List<Double>>();
        var callsKnown = false;
        var recent = join(recentF);
        if (recent != null) {
            callsKnown = true;
            for (var hit : recent) {
                if (!hit.traceId().isBlank() && !hit.agent().isBlank()) {
                    traces.put(hit.traceId(), hit.agent());
                }
            }
        }
        if (counted(join(rootsF), env, traces, latency)) {
            callsKnown = true;
        }
        var localCalls = countHits(recent);
        var previous = new HashMap<String, Long>();
        var span = join(spanF);
        var prevKnown = recent != null && span != null;
        if (prevKnown) {
            var recentIds = new HashSet<String>();
            for (var hit : recent) {
                recentIds.add(hit.traceId());
            }
            var seen = new HashSet<String>();
            for (var hit : span) {
                if (hit.traceId().isBlank() || hit.agent().isBlank() || recentIds.contains(hit.traceId())
                        || !seen.add(hit.traceId())) {
                    continue;
                }
                previous.merge(hit.agent(), 1L, Long::sum);
            }
        }
        Map<String, Long> totals = new HashMap<>();
        var all = join(allF);
        var totalsKnown = false;
        if (all != null) {
            totalsKnown = true;
            var seen = new HashSet<String>();
            for (var hit : all) {
                if (hit.traceId().isBlank() || hit.agent().isBlank() || !seen.add(hit.traceId())) {
                    continue;
                }
                totals.merge(hit.agent(), 1L, Long::sum);
            }
        }
        Map<String, Long> calls = Map.of();
        if (callsKnown) {
            calls = new HashMap<>();
            for (var agent : traces.values()) {
                calls.merge(agent, 1L, Long::sum);
            }
            if (!totalsKnown) {
                totalsKnown = true;
                totals.putAll(calls);
            }
            for (var entry : calls.entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        var p95 = new HashMap<String, Double>();
        latency.forEach((agent, samples) -> {
            var value = percentile(samples);
            if (value != null) {
                p95.put(agent, value);
            }
        });
        var keys = join(keysF);
        Map<String, Double> cost = null;
        var budgets = new HashMap<String, Double>();
        if (keys != null) {
            cost = new HashMap<>();
            for (var key : keys) {
                var keyEnv = CostService.envOf(key.alias());
                if (!"all".equals(env) && !env.equals(keyEnv)) {
                    continue;
                }
                var agent = CostService.agentOf(key.alias());
                cost.merge(agent, key.spentCny(), Double::sum);
                budgets.merge(agent, key.dailyBudgetCny(), Double::sum);
            }
        }
        var scores = new HashMap<String, Double>();
        join(qualityF).forEach((agent, score) -> {
            if (score != null) {
                scores.put(agent, score);
            }
        });
        join(experimentF).forEach(scores::put);
        return new Snapshot(callsKnown, calls, prevKnown, localCalls, previous, totalsKnown, totals, p95,
                cost != null, cost == null ? Map.of() : cost, budgets, scores);
    }

    private static Map<String, Long> countHits(List<SavedTraces.TraceHit> hits) {
        var counts = new HashMap<String, Long>();
        if (hits == null) {
            return counts;
        }
        var seen = new HashSet<String>();
        for (var hit : hits) {
            if (hit.traceId().isBlank() || hit.agent().isBlank() || !seen.add(hit.traceId())) {
                continue;
            }
            counts.merge(hit.agent(), 1L, Long::sum);
        }
        return counts;
    }

    private List<LiteLlmClient.VirtualKey> loadKeys() {
        try {
            return gateway.keys();
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("未配置")) {
                return null;
            }
            throw e;
        }
    }

    private Map<String, Double> qualityScores() {
        try {
            return quality.byAgent();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private Map<String, Double> experimentScores() {
        try {
            return evaluations.latestScoreByAgent();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private List<SavedTraces.TraceHit> localHits(Instant from, String env) {
        try {
            return saved.since(from, env);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean counted(LangfuseClient.RootPage page, String env, Map<String, String> traces,
                                   Map<String, List<Double>> latency) {
        if (page == null) {
            return false;
        }
        for (var call : page.rows()) {
            if (call.traceId().isBlank() || call.agent().isBlank()) {
                continue;
            }
            var callEnv = CostService.envOf(call.keyAlias());
            if (!"all".equals(env) && (callEnv.isBlank() || !callEnv.equals(env))) {
                continue;
            }
            traces.putIfAbsent(call.traceId(), call.agent());
            if (latency != null && call.latencySeconds() != null) {
                latency.computeIfAbsent(call.agent(), key -> new ArrayList<>()).add(call.latencySeconds());
            }
        }
        return true;
    }

    static Double percentile(List<Double> samples) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        var sorted = new ArrayList<>(samples);
        sorted.sort(Double::compareTo);
        var index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return Math.round(sorted.get(Math.max(index, 0)) * 10.0) / 10.0;
    }

    public record Snapshot(boolean callsKnown, Map<String, Long> calls, boolean previousKnown,
                           Map<String, Long> localCalls, Map<String, Long> previous,
                           boolean totalsKnown, Map<String, Long> totals, Map<String, Double> p95,
                           boolean costKnown, Map<String, Double> cost, Map<String, Double> budgets,
                           Map<String, Double> scores) {
        public Double trendPct() {
            if (!previousKnown) {
                return null;
            }
            var current = localCalls.values().stream().mapToLong(Long::longValue).sum();
            var before = previous.values().stream().mapToLong(Long::longValue).sum();
            if (before == 0) {
                return null;
            }
            return Math.round((current - before) * 1000.0 / before) / 10.0;
        }

        public double costTotal() {
            return cost.values().stream().mapToDouble(Double::doubleValue).sum();
        }

        public long callTotal() {
            return calls.values().stream().mapToLong(Long::longValue).sum();
        }
    }
}
