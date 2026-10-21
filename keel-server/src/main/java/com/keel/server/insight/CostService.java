package com.keel.server.insight;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.ConfigAudit;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CostService {
    private static final BigDecimal BUDGET_MIN = new BigDecimal("10");
    private static final BigDecimal BUDGET_MAX = new BigDecimal("200");
    private static final BigDecimal BUDGET_STEP = new BigDecimal("5");
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();

    private final LiteLlmClient gateway;
    private final LangfuseClient langfuse;
    private final ConfigAudit audit;
    private final AtomicLong readGeneration = new AtomicLong();
    private List<LiteLlmClient.Spend> spendCache = List.of();
    private Instant spendCachedFrom = Instant.EPOCH;
    private long spendCachedUntil;
    private long spendCachedGeneration = -1;

    public CostService(LiteLlmClient gateway) {
        this(gateway, new LangfuseClient("", "", ""), (agent, env) -> { });
    }

    @Autowired
    public CostService(LiteLlmClient gateway, LangfuseClient langfuse, ConfigAudit audit) {
        this.gateway = gateway;
        this.langfuse = langfuse;
        this.audit = audit;
    }

    public com.fasterxml.jackson.databind.JsonNode logicalRouteTable(String operation,Map<String,Object> body) {
        try {
            var response=gateway.logicalRouteTable(operation,body);
            if(operation!=null && !"replay".equals(operation)) {
                evict();audit.record("keel-llm","prod");
            }
            return response;
        } catch(IllegalStateException error) {
            var message=error.getMessage()==null?"":error.getMessage();
            if(message.contains(" 400")) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,message);
            throw error;
        }
    }

    public LiteLlmClient.RoutePolicy routePolicy() {
        return gateway.routePolicy();
    }

    public LiteLlmClient.RoutePolicy publishRoute(String objective) {
        if (!"balanced".equals(objective) && !"cost".equals(objective) && !"latency".equals(objective)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "objective 只能是 balanced、cost 或 latency");
        }
        try {
            var policy = gateway.publishRoute(objective);
            evict();
            audit.record("keel-llm", "prod");
            return policy;
        } catch (IllegalStateException e) {
            throw routeFailure(e);
        }
    }

    public LiteLlmClient.RoutePolicy rollbackRoute() {
        try {
            var policy = gateway.rollbackRoute();
            evict();
            audit.record("keel-llm", "prod");
            return policy;
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains(" 400")) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "没有可回滚的上一版路由策略");
            }
            throw e;
        }
    }

    public LiteLlmClient.Preview previewRoute(String alias, String model) {
        if (alias == null || alias.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "alias 不能为空");
        }
        try {
            return gateway.previewRoute(alias, model == null ? "" : model);
        } catch (IllegalStateException e) {
            throw routeFailure(e);
        }
    }

    private static RuntimeException routeFailure(IllegalStateException error) {
        var message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains(" 400")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "路由策略没有被薄网关接受");
        }
        throw error;
    }

    public List<LiteLlmClient.AlertRule> alertRules() {
        return gateway.alertRules();
    }

    public LiteLlmClient.AlertRule addAlert(String name, String metric, double threshold, String window, String groupBy) {
        return gateway.addAlert(name, metric, threshold, window, groupBy);
    }

    public Result cost() {
        return cost("all", null);
    }

    public Result cost(String env, String range) {
        try {
            return load(env == null || env.isBlank() ? "all" : env, range);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("未配置")) {
                return new Result(0, 0, Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), 0);
            }
            throw e;
        }
    }

    /**
     * Writes the new daily budget onto the virtual key, then audits. Audit failure aborts the response
     * after the gateway has the new value, so the caller can retry the audit by saving again.
     */
    public void probe(String model) {
        gateway.probe(model);
        evict();
    }

    public long readGeneration() {
        return readGeneration.get();
    }

    public void evict() {
        readGeneration.incrementAndGet();
        synchronized (this) {
            spendCache = List.of();
            spendCachedUntil = 0;
        }
    }

    public List<LiteLlmClient.LogicalRoute> logicalRoutes() {
        try {
            return gateway.logicalRoutes();
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    public List<LiteLlmClient.Channel> channels() {
        try {
            return gateway.channels();
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    public LiteLlmClient.Channel addChannel(String name, String model, String env, int rpm) {
        try {
            var created = gateway.addChannel(name, model, env, rpm);
            evict();
            audit.record("keel-llm", env == null || env.isBlank() ? "prod" : env);
            return created;
        } catch (IllegalStateException error) {
            throw channelFailure(error);
        }
    }

    public LiteLlmClient.SwitchResult switchChannel(String id) {
        try {
            var moved = gateway.switchChannel(id);
            evict();
            audit.record("keel-llm", "prod");
            return moved;
        } catch (IllegalStateException error) {
            throw channelFailure(error);
        }
    }

    public LiteLlmClient.BreakerResult simulateChannel(String id) {
        try {
            var result = gateway.simulateChannel(id);
            evict();
            return result;
        } catch (IllegalStateException error) {
            throw channelFailure(error);
        }
    }

    public LiteLlmClient.BreakerResult probeChannel(String id) {
        try {
            var result = gateway.probeChannel(id);
            evict();
            audit.record("keel-llm", "prod");
            return result;
        } catch (IllegalStateException error) {
            throw channelFailure(error);
        }
    }

    private static RuntimeException channelFailure(IllegalStateException error) {
        var message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains(" 404")) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "渠道不存在");
        }
        if (message.contains(" 400")) {
            var detail = message.substring(message.indexOf(" 400") + 4).trim();
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, detail.isBlank() ? "渠道操作没有被薄网关接受" : detail);
        }
        throw error;
    }

    public void updateBudget(String alias, BigDecimal dailyBudgetCny) {
        updateBudget(alias, dailyBudgetCny, Map.of());
    }

    public void updateBudget(String alias, BigDecimal dailyBudgetCny, Map<String, Integer> limits) {
        if (alias == null || alias.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "别名不能为空");
        }
        if (!budgetOnSlider(dailyBudgetCny)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "日预算须在 ¥10 到 ¥200 之间，且为 5 的倍数");
        }
        if (limits.values().stream().anyMatch(value -> value != null && value <= 0)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "RPM、TPM、并发须为正整数，空白表示不限制");
        }
        updateBudget(alias, dailyBudgetCny, limits, null);
    }

    public void updateBudget(String alias, BigDecimal dailyBudgetCny, Map<String, Integer> limits,
                             List<LiteLlmClient.Share> channels) {
        if (alias == null || alias.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "别名不能为空");
        }
        if (dailyBudgetCny != null && !budgetOnSlider(dailyBudgetCny)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "日预算须在 ¥10 到 ¥200 之间，且为 5 的倍数");
        }
        if (dailyBudgetCny == null && (channels == null || channels.isEmpty())) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "dailyBudgetCny 缺失");
        }
        if (limits != null && limits.values().stream().anyMatch(value -> value != null && value <= 0)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "RPM、TPM、并发须为正整数，空白表示不限制");
        }
        try {
            gateway.updateBudget(alias, dailyBudgetCny, limits == null ? Map.of() : limits, channels);
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains(" 404")) {
                throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
            }
            if (message.contains(" 400")) {
                var detail = message.substring(message.indexOf(" 400") + 4).trim();
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, detail.isBlank() ? "配额没有被薄网关接受" : detail);
            }
            throw e;
        }
        evict();
        audit.record(agentOf(alias), envOf(alias));
    }

    public record Ledger(List<LiteLlmClient.Spend> bills, List<LiteLlmClient.VirtualKey> keys,
                         List<LiteLlmClient.Model> models, List<LiteLlmClient.AlertRule> rules) {}
    public Ledger ledger() { return new Ledger(gateway.spendAll(),gateway.keys(),gateway.models(),gateway.alertRules()); }
    /** Overview does not need the model page's Langfuse generation/latency sampling. */
    public Result summarize(Ledger ledger,String env,String range) {
        var rows=ledger.bills().stream().filter(row->inWindow(row.ts(),since(range,Instant.now())) && inEnv(row.alias(),env)).toList();
        var byAgent=new LinkedHashMap<String,Double>(); var byModel=new LinkedHashMap<String,Double>();
        double total=0;
        for(var row:rows) if("SETTLED".equals(row.status()) && row.costCny()!=null) {
            total+=row.costCny();byAgent.merge(agentOf(row.alias()),row.costCny(),Double::sum);byModel.merge(row.model(),row.costCny(),Double::sum);
        }
        var keys=ledger.keys().stream().filter(key->inEnv(key.alias(),env)).map(key->new Key(key.alias(),agentOf(key.alias()),envOf(key.alias()),
                key.models(),key.dailyBudgetCny(),key.spentCny(),key.reservedCny(),key.blocked(),key.version(),key.rpm(),key.tpm(),key.concurrency(),key.shares())).toList();
        return new Result(total,rows.size(),byAgent,byModel,ledger.models(),keys,List.of(),rows,(int)rows.stream().filter(row->"PENDING".equals(row.status())).count());
    }

    private Result load(String env, String range) {
        var now = Instant.now();
        var since = since(range, now);
        var rowsFuture = CompletableFuture.supplyAsync(() -> spendWindow(since), IO);
        var keysFuture = CompletableFuture.supplyAsync(gateway::keys, IO);
        var modelsFuture = CompletableFuture.supplyAsync(gateway::models, IO);
        var rows = await(rowsFuture).stream()
                .filter(row -> inWindow(row.ts(), since) && inEnv(row.alias(), env))
                .toList();
        var gatewayKeys = await(keysFuture);
        var catalog = await(modelsFuture);
        var byAgent = new LinkedHashMap<String, Double>();
        double total = 0;
        var byModel = new LinkedHashMap<String, Double>();
        for (var row : rows) {
            if (row.costCny() != null && "SETTLED".equals(row.status())) {
                total += row.costCny();
                byAgent.merge(agentOf(row.alias()), row.costCny(), Double::sum);
                byModel.merge(row.model(), row.costCny(), Double::sum);
            }
        }
        var keys = mapKeys(gatewayKeys, env);
        var stats = stats(catalog, rows, keys, env, since, now);
        int pending = (int) rows.stream().filter(row -> "PENDING".equals(row.status())).count();
        return new Result(total, rows.size(), byAgent, byModel, catalog, keys, stats, rows, pending);
    }

    public List<Key> keysIn(String env) {
        return mapKeys(gateway.keys(), env == null || env.isBlank() ? "all" : env);
    }

    public List<LiteLlmClient.Spend> billsIn(String env, String range) {
        var scope = env == null || env.isBlank() ? "all" : env;
        var cutoff = since(range, Instant.now());
        return spendWindow(cutoff).stream()
                .filter(row -> inWindow(row.ts(), cutoff) && inEnv(row.alias(), scope))
                .toList();
    }

    private static List<Key> mapKeys(List<LiteLlmClient.VirtualKey> keys, String env) {
        return keys.stream()
                .filter(key -> inEnv(key.alias(), env))
                .map(key -> new Key(key.alias(), agentOf(key.alias()), envOf(key.alias()), key.models(),
                        key.dailyBudgetCny(), key.spentCny(), key.reservedCny(), key.blocked(), key.version(),
                        key.rpm(), key.tpm(), key.concurrency(),
                        key.shares() == null ? List.of() : key.shares()))
                .toList();
    }

    /** Windowed spend, reused for 30 seconds when a later window is inside the cached one. */
    public synchronized List<LiteLlmClient.Spend> spendWindow(Instant since) {
        var cutoff = since == null ? Instant.EPOCH : since;
        var now = System.currentTimeMillis();
        var generation = readGeneration.get();
        if (generation == spendCachedGeneration && spendCachedUntil > now && !cutoff.isBefore(spendCachedFrom)) {
            return spendCache.stream().filter(row -> inWindow(row.ts(), cutoff)).toList();
        }
        var rows = gateway.spendSince(cutoff);
        spendCache = rows;
        spendCachedFrom = cutoff;
        spendCachedUntil = now + 30_000;
        spendCachedGeneration = generation;
        return rows;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw error;
        }
    }

    private List<ModelStat> stats(List<LiteLlmClient.Model> catalog, List<LiteLlmClient.Spend> rows, List<Key> keys,
                                  String env, Instant since, Instant until) {
        var calls = new LinkedHashMap<String, Integer>();
        var timeouts = new LinkedHashMap<String, Integer>();
        var latency = new LinkedHashMap<String, List<Double>>();
        var cost = new LinkedHashMap<String, Double>();
        var instrumented = false;
        for (var row : rows) {
            calls.merge(row.model(), 1, Integer::sum);
            if (row.costCny() != null && "SETTLED".equals(row.status())) {
                cost.merge(row.model(), row.costCny(), Double::sum);
            }
            if (row.timedOut()) {
                timeouts.merge(row.model(), 1, Integer::sum);
                instrumented = true;
            }
            if (row.latencyMs() != null) {
                latency.computeIfAbsent(row.model(), name -> new ArrayList<>()).add(row.latencyMs() / 1000.0);
                instrumented = true;
            }
        }
        if (!instrumented) {
            var sampled = sample(generations(since, until, env));
            if (!sampled.calls.isEmpty()) {
                if (calls.isEmpty()) {
                    calls.putAll(sampled.calls);
                    timeouts.putAll(sampled.timeouts);
                }
                latency.putAll(sampled.latency);
                if (calls.equals(sampled.calls)) {
                    timeouts.putAll(sampled.timeouts);
                    instrumented = true;
                }
            }
        }
        var names = new LinkedHashSet<String>();
        if ("all".equals(env)) {
            catalog.forEach(model -> names.add(model.name()));
        }
        names.addAll(calls.keySet());
        names.addAll(cost.keySet());
        for (var key : keys) {
            if (key.models() != null) {
                names.addAll(key.models());
            }
        }
        var roles = roles(keys);
        var providers = new LinkedHashMap<String, String>();
        var priced = new LinkedHashMap<String, Boolean>();
        for (var model : catalog) {
            providers.put(model.name(), provider(model.provider(), model.name()));
            priced.put(model.name(), model.priceConfigured());
        }
        var stats = new ArrayList<ModelStat>();
        for (var name : names) {
            var count = calls.getOrDefault(name, 0);
            Double rate = instrumented && count > 0 ? timeouts.getOrDefault(name, 0) / (double) count : null;
            var catalogModel = catalog.stream().filter(model -> name.equals(model.name())).findFirst().orElse(null);
            var latest = rows.stream().filter(row -> name.equals(row.model()) && "SETTLED".equals(row.status())).findFirst().orElse(null);
            var version = catalogModel == null ? "" : catalogModel.priceVersion();
            var drift = latest != null && !latest.priceVersion().isBlank() && !latest.priceVersion().equals(version);
            var health = latest != null ? "LIVE"
                    : catalogModel != null && "PROBED".equals(catalogModel.health()) ? "PROBED"
                    : catalogModel != null && catalogModel.priceConfigured() ? "CONFIGURED" : "";
            stats.add(new ModelStat(
                    name,
                    providers.getOrDefault(name, provider("", name)),
                    roles.getOrDefault(name, ""),
                    count,
                    formatP95(latency.getOrDefault(name, List.of())),
                    rate,
                    cost.getOrDefault(name, 0d),
                    statusOf(rate),
                    priced.getOrDefault(name, true),
                    version,
                    catalogModel == null ? null : catalogModel.cacheCnyPerToken(),
                    health,
                    drift || (catalogModel != null && catalogModel.drift()),
                    catalogModel == null ? null : catalogModel.inputCnyPerToken(),
                    catalogModel == null ? null : catalogModel.outputCnyPerToken(),
                    catalogModel != null && catalogModel.embedding()));
        }
        return stats;
    }

    private static Sample sample(List<LangfuseClient.Generation> generations) {
        var calls = new LinkedHashMap<String, Integer>();
        var timeouts = new LinkedHashMap<String, Integer>();
        var latency = new LinkedHashMap<String, List<Double>>();
        for (var gen : generations) {
            calls.merge(gen.model(), 1, Integer::sum);
            if (gen.timedOut()) {
                timeouts.merge(gen.model(), 1, Integer::sum);
            }
            if (gen.latencySeconds() != null) {
                latency.computeIfAbsent(gen.model(), name -> new ArrayList<>()).add(gen.latencySeconds());
            }
            if (!gen.fallbackFrom().isBlank() && !gen.fallbackFrom().equals(gen.model())) {
                calls.merge(gen.fallbackFrom(), 1, Integer::sum);
                timeouts.merge(gen.fallbackFrom(), 1, Integer::sum);
            }
        }
        return new Sample(calls, timeouts, latency);
    }

    private record Sample(Map<String, Integer> calls, Map<String, Integer> timeouts, Map<String, List<Double>> latency) {}

    private List<LangfuseClient.Generation> generations(Instant since, Instant until, String env) {
        var rows = langfuse.generations(since, until);
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(row -> inEnv(row.keyAlias(), env) || (row.keyAlias().isBlank() && "all".equals(env))).toList();
    }

    private static Map<String, String> roles(List<Key> keys) {
        var defaults = new LinkedHashSet<String>();
        var fallbacks = new LinkedHashSet<String>();
        var embeddingUsers = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var key : keys) {
            var models = key.models();
            if (models == null || models.isEmpty()) {
                continue;
            }
            defaults.add(models.get(0));
            for (int i = 1; i < models.size(); i++) {
                fallbacks.add(models.get(i));
            }
            for (var model : models) {
                if (model.toLowerCase(Locale.ROOT).contains("embedding")) {
                    embeddingUsers.computeIfAbsent(model, name -> new LinkedHashSet<>()).add(key.agent());
                }
            }
        }
        var roles = new LinkedHashMap<String, String>();
        defaults.forEach(model -> roles.put(model, "主力"));
        fallbacks.forEach(model -> roles.putIfAbsent(model, "降级备选"));
        embeddingUsers.forEach((model, users) -> {
            if (users.size() == 1) {
                roles.put(model, "向量化（" + users.iterator().next() + "）");
            } else {
                roles.put(model, "向量化");
            }
        });
        roles.putIfAbsent("deepseek-flash", "降级备选");
        return roles;
    }

    static String provider(String reported, String model) {
        if (reported != null && !reported.isBlank()) {
            return reported;
        }
        var name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (name.startsWith("qwen") || name.contains("embedding")) {
            return "DashScope";
        }
        if (name.startsWith("deepseek")) {
            return "DeepSeek";
        }
        return "";
    }

    private static String formatP95(List<Double> seconds) {
        var value = AgentUsageService.percentile(seconds);
        if (value == null) {
            return null;
        }
        return String.format(Locale.US, "%.1fs", value);
    }

    private static String statusOf(Double timeoutRate) {
        if (timeoutRate == null) {
            return "ok";
        }
        if (timeoutRate >= 0.1) {
            return "bad";
        }
        if (timeoutRate >= 0.02) {
            return "warn";
        }
        return "ok";
    }

    private static boolean budgetOnSlider(BigDecimal dailyBudgetCny) {
        if (dailyBudgetCny == null) {
            return false;
        }
        return dailyBudgetCny.compareTo(BUDGET_MIN) >= 0
                && dailyBudgetCny.compareTo(BUDGET_MAX) <= 0
                && dailyBudgetCny.remainder(BUDGET_STEP).signum() == 0;
    }

    private static Instant since(String range, Instant now) {
        if (range == null || range.isBlank()) {
            return Instant.parse("2020-01-01T00:00:00Z");
        }
        return switch (range) {
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            case "today" -> now.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
                    .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
            case "all" -> Instant.parse("2020-01-01T00:00:00Z");
            default -> now.minus(Duration.ofHours(24));
        };
    }

    private static boolean inWindow(String ts, Instant since) {
        if (ts == null || ts.isBlank()) {
            return true;
        }
        try {
            return !Instant.parse(ts).isBefore(since);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean inEnv(String alias, String env) {
        if (env == null || env.isBlank() || "all".equals(env)) {
            return true;
        }
        if (alias == null || alias.isBlank()) {
            return false;
        }
        return env.equals(envOf(alias));
    }

    public static String agentOf(String alias) {
        for (var suffix : List.of("-prod", "-staging", "-test", "-dev")) {
            if (alias.endsWith(suffix)) {
                return alias.substring(0, alias.length() - suffix.length());
            }
        }
        return alias;
    }

    static String envOf(String alias) {
        for (var env : List.of("prod", "staging", "test", "dev")) {
            if (alias.endsWith("-" + env)) {
                return env;
            }
        }
        return "";
    }

    public record Key(String alias, String agent, String env, List<String> models, double dailyBudgetCny,
                      double spentCny, double reservedCny, boolean blocked, int version,
                      Integer rpm, Integer tpm, Integer concurrency, List<LiteLlmClient.Share> shares) {
        public Key(String alias, String agent, String env, List<String> models, double dailyBudgetCny,
                   double spentCny, double reservedCny, boolean blocked, int version,
                   Integer rpm, Integer tpm, Integer concurrency) {
            this(alias, agent, env, models, dailyBudgetCny, spentCny, reservedCny, blocked, version,
                    rpm, tpm, concurrency, List.of());
        }
    }

    public record ModelStat(String name, String provider, String role, int calls, String p95, Double timeoutRate,
                            double costCny, String status, boolean priceConfigured, String priceVersion,
                            Double cacheCnyPerToken, String health, boolean drift,
                            Double inputCnyPerToken, Double outputCnyPerToken, boolean embedding) {}

    public record Result(double totalCny, int calls, Map<String, Double> byAgent, Map<String, Double> byModel,
                         List<LiteLlmClient.Model> models, List<Key> keys, List<ModelStat> stats,
                         List<LiteLlmClient.Spend> bills, int pendingCount) {}
}
