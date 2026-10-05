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

@Service
public class CostService {
    private static final BigDecimal BUDGET_MIN = new BigDecimal("10");
    private static final BigDecimal BUDGET_MAX = new BigDecimal("200");
    private static final BigDecimal BUDGET_STEP = new BigDecimal("5");

    private final LiteLlmClient gateway;
    private final LangfuseClient langfuse;
    private final ConfigAudit audit;

    public CostService(LiteLlmClient gateway) {
        this(gateway, new LangfuseClient("", "", ""), (agent, env) -> { });
    }

    @Autowired
    public CostService(LiteLlmClient gateway, LangfuseClient langfuse, ConfigAudit audit) {
        this.gateway = gateway;
        this.langfuse = langfuse;
        this.audit = audit;
    }

    public Result cost() {
        return cost("all", null);
    }

    public Result cost(String env, String range) {
        try {
            return load(env == null || env.isBlank() ? "all" : env, range);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("未配置")) {
                return new Result(0, 0, Map.of(), Map.of(), List.of(), List.of(), List.of());
            }
            throw e;
        }
    }

    /**
     * Writes the new daily budget onto the virtual key, then audits. Audit failure aborts the response
     * after the gateway has the new value, so the caller can retry the audit by saving again.
     */
    public void updateBudget(String alias, BigDecimal dailyBudgetCny) {
        if (alias == null || alias.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "别名不能为空");
        }
        if (!budgetOnSlider(dailyBudgetCny)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "日预算须在 ¥10 到 ¥200 之间，且为 5 的倍数");
        }
        try {
            gateway.updateBudget(alias, dailyBudgetCny);
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            if (message.endsWith(" 404")) {
                throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
            }
            throw e;
        }
        audit.record(agentOf(alias), envOf(alias));
    }

    private Result load(String env, String range) {
        var now = Instant.now();
        var since = since(range, now);
        var rows = gateway.spendAll().stream()
                .filter(row -> inWindow(row.ts(), since) && inEnv(row.alias(), env))
                .toList();
        var byAgent = new LinkedHashMap<String, Double>();
        double total = 0;
        var byModel = new LinkedHashMap<String, Double>();
        for (var row : rows) {
            total += row.costCny();
            byAgent.merge(agentOf(row.alias()), row.costCny(), Double::sum);
            byModel.merge(row.model(), row.costCny(), Double::sum);
        }
        var keys = gateway.keys().stream()
                .filter(key -> inEnv(key.alias(), env))
                .map(key -> new Key(key.alias(), agentOf(key.alias()), envOf(key.alias()), key.models(),
                        key.dailyBudgetCny(), key.spentCny(), key.blocked()))
                .toList();
        var stats = stats(gateway.models(), rows, keys, env, since, now);
        return new Result(total, rows.size(), byAgent, byModel, gateway.models(), keys, stats);
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
            cost.merge(row.model(), row.costCny(), Double::sum);
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
        catalog.forEach(model -> names.add(model.name()));
        names.addAll(calls.keySet());
        names.addAll(cost.keySet());
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
            stats.add(new ModelStat(
                    name,
                    providers.getOrDefault(name, provider("", name)),
                    roles.getOrDefault(name, ""),
                    count,
                    formatP95(latency.getOrDefault(name, List.of())),
                    rate,
                    cost.getOrDefault(name, 0d),
                    statusOf(rate),
                    priced.getOrDefault(name, true)));
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
        roles.putIfAbsent("deepseek-v3", "降级备选");
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

    static String agentOf(String alias) {
        for (var suffix : List.of("-prod", "-staging", "-dev")) {
            if (alias.endsWith(suffix)) {
                return alias.substring(0, alias.length() - suffix.length());
            }
        }
        return alias;
    }

    static String envOf(String alias) {
        for (var env : List.of("prod", "staging", "dev")) {
            if (alias.endsWith("-" + env)) {
                return env;
            }
        }
        return "";
    }

    public record Key(String alias, String agent, String env, List<String> models, double dailyBudgetCny, double spentCny, boolean blocked) {}

    public record ModelStat(String name, String provider, String role, int calls, String p95, Double timeoutRate,
                            double costCny, String status, boolean priceConfigured) {}

    public record Result(double totalCny, int calls, Map<String, Double> byAgent, Map<String, Double> byModel,
                         List<LiteLlmClient.Model> models, List<Key> keys, List<ModelStat> stats) {}
}
