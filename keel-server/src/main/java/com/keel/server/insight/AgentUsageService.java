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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列表卡片上的三项指标。调用按 trace 去重：本机探针和 Langfuse 根节点对上同一个 trace 只算一次。
 * 成本用薄网关按上海时区日切的 spentCny，和日预算同一口径。评测分来自 Langfuse v3 scores。
 * 读不到的来源保持 null，不把故障写成 0。
 */
@Service
public class AgentUsageService {
    private static final Duration TTL = Duration.ofSeconds(60);
    private final LangfuseClient langfuse;
    private final LiteLlmClient gateway;
    private final QualityService quality;
    private final SavedTraces saved;
    private final Clock clock;
    private Snapshot cached;
    private String cachedEnv;
    private Instant cachedAt;

    @Autowired
    public AgentUsageService(LangfuseClient langfuse, LiteLlmClient gateway, QualityService quality, SavedTraces saved) {
        this(langfuse, gateway, quality, saved, Clock.systemUTC());
    }

    AgentUsageService(LangfuseClient langfuse, LiteLlmClient gateway, QualityService quality, SavedTraces saved, Clock clock) {
        this.langfuse = langfuse;
        this.gateway = gateway;
        this.quality = quality;
        this.saved = saved == null ? SavedTraces.EMPTY : saved;
        this.clock = clock;
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

    private synchronized Snapshot snapshot(String env) {
        var now = clock.instant();
        if (cached != null && env.equals(cachedEnv) && cachedAt.plus(TTL).isAfter(now)) {
            return cached;
        }
        var loaded = load(env, now);
        cached = loaded;
        cachedEnv = env;
        cachedAt = now;
        return loaded;
    }

    private Snapshot load(String env, Instant now) {
        var since = now.minus(Duration.ofHours(24));
        var traces = new LinkedHashMap<String, String>();
        var latency = new HashMap<String, List<Double>>();
        var callsKnown = false;
        try {
            for (var hit : saved.since(since, env)) {
                if (!hit.traceId().isBlank() && !hit.agent().isBlank()) {
                    traces.put(hit.traceId(), hit.agent());
                }
            }
            callsKnown = true;
        } catch (RuntimeException ignored) {
            // 本机表不可用时仍可以用 Langfuse。
        }
        if (counted(langfuse.roots(since, now), env, traces, latency)) {
            callsKnown = true;
        }
        var previous = new HashMap<String, Long>();
        var prevTraces = new LinkedHashMap<String, String>();
        var prevKnown = counted(langfuse.roots(now.minus(Duration.ofHours(48)), since), env, prevTraces, null);
        if (prevKnown) {
            for (var agent : prevTraces.values()) {
                previous.merge(agent, 1L, Long::sum);
            }
        }
        Map<String, Long> totals = Map.of();
        var totalsKnown = false;
        var allTime = langfuse.roots(Instant.parse("2020-01-01T00:00:00Z"), now);
        if (allTime != null && allTime.complete()) {
            totalsKnown = true;
            var all = new LinkedHashMap<String, String>();
            try {
                for (var hit : saved.since(Instant.parse("2020-01-01T00:00:00Z"), env)) {
                    if (!hit.traceId().isBlank() && !hit.agent().isBlank()) {
                        all.put(hit.traceId(), hit.agent());
                    }
                }
            } catch (RuntimeException ignored) {
                // 本机表不可用时，总调用只计 Langfuse 里能读全的根节点。
            }
            counted(allTime, env, all, null);
            totals = new HashMap<>();
            for (var agent : all.values()) {
                totals.merge(agent, 1L, Long::sum);
            }
        }
        Map<String, Long> calls = Map.of();
        if (callsKnown) {
            calls = new HashMap<>();
            for (var agent : traces.values()) {
                calls.merge(agent, 1L, Long::sum);
            }
        }
        var p95 = new HashMap<String, Double>();
        latency.forEach((agent, samples) -> {
            var value = percentile(samples);
            if (value != null) {
                p95.put(agent, value);
            }
        });
        Map<String, Double> cost = null;
        var budgets = new HashMap<String, Double>();
        try {
            cost = new HashMap<>();
            for (var key : gateway.keys()) {
                var keyEnv = CostService.envOf(key.alias());
                if (!"all".equals(env) && !env.equals(keyEnv)) {
                    continue;
                }
                var agent = CostService.agentOf(key.alias());
                cost.merge(agent, key.spentCny(), Double::sum);
                budgets.merge(agent, key.dailyBudgetCny(), Double::sum);
            }
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            if (!message.contains("未配置")) {
                throw e;
            }
            cost = null;
        }
        return new Snapshot(callsKnown, calls, prevKnown, previous, totalsKnown, totals, p95,
                cost != null, cost == null ? Map.of() : cost, budgets, quality.byAgent());
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

    public record Snapshot(boolean callsKnown, Map<String, Long> calls, boolean previousKnown, Map<String, Long> previous,
                           boolean totalsKnown, Map<String, Long> totals, Map<String, Double> p95,
                           boolean costKnown, Map<String, Double> cost, Map<String, Double> budgets,
                           Map<String, Double> scores) {
        public Double trendPct() {
            if (!callsKnown || !previousKnown) {
                return null;
            }
            var current = calls.values().stream().mapToLong(Long::longValue).sum();
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
