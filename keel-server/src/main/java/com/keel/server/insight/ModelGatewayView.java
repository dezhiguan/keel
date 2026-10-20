package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Assembles one models-page section and keeps a 30 second success cache. */
public class ModelGatewayView {
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
    private static final long TTL_MS = 30_000;

    private final CostService costs;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private long seen = -1;

    public ModelGatewayView(CostService costs) {
        this.costs = costs;
    }

    public Map<String, Object> get(String env, String range, String section) {
        var scope = env == null || env.isBlank() ? "all" : env;
        var window = range == null || range.isBlank() ? "24h" : range;
        var part = section == null || section.isBlank() ? "all" : section;
        var generation = costs.readGeneration();
        var key = generation + "\0" + scope + "\0" + window + "\0" + part;
        synchronized (cache) {
            if (generation != seen) {
                cache.clear();
                seen = generation;
            }
            var hit = cache.get(key);
            if (hit != null && hit.until > System.currentTimeMillis()) {
                return hit.body;
            }
        }
        var body = switch (part) {
            case "channels" -> channels(scope, window);
            case "keys" -> keys(scope, window);
            case "routes" -> routes(scope);
            default -> all(scope, window);
        };
        synchronized (cache) {
            if (costs.readGeneration() == generation) {
                cache.put(key, new Cached(System.currentTimeMillis() + TTL_MS, body));
                seen = generation;
            } else {
                cache.clear();
                seen = costs.readGeneration();
            }
        }
        return body;
    }

    private Map<String, Object> channels(String env, String range) {
        var costFuture = CompletableFuture.supplyAsync(() -> costs.cost(env, range), IO);
        var channelsFuture = CompletableFuture.supplyAsync(costs::channels, IO);
        var cost = await(costFuture);
        var body = new LinkedHashMap<String, Object>();
        body.put("models", models(cost));
        body.put("totalCny", cost.totalCny());
        body.put("keys", keys(cost.keys()));
        body.put("pendingCount", cost.pendingCount());
        body.put("channels", channels(await(channelsFuture)));
        return body;
    }

    private Map<String, Object> keys(String env, String range) {
        var billsFuture = CompletableFuture.supplyAsync(() -> costs.billsIn(env, range), IO);
        var keysFuture = CompletableFuture.supplyAsync(() -> costs.keysIn(env), IO);
        var channelsFuture = CompletableFuture.supplyAsync(costs::channels, IO);
        var routesFuture = CompletableFuture.supplyAsync(costs::logicalRoutes, IO);
        var body = new LinkedHashMap<String, Object>();
        body.put("keys", keys(await(keysFuture)));
        body.put("channels", channels(await(channelsFuture)));
        body.put("logicalRoutes", routes(await(routesFuture)));
        body.put("bills", bills(await(billsFuture).stream().filter(row -> "FAILED".equals(row.status())).toList()));
        return body;
    }

    private Map<String, Object> routes(String env) {
        var policyFuture = CompletableFuture.supplyAsync(costs::routePolicy, IO);
        var routesFuture = CompletableFuture.supplyAsync(costs::logicalRoutes, IO);
        var keysFuture = CompletableFuture.supplyAsync(() -> costs.keysIn(env), IO);
        var channelsFuture = CompletableFuture.supplyAsync(costs::channels, IO);
        var body = new LinkedHashMap<String, Object>();
        var policy = await(policyFuture);
        if (policy != null) {
            body.put("routePolicy", Map.of("version", policy.version(), "objective", policy.objective()));
        }
        body.put("logicalRoutes", routes(await(routesFuture)));
        body.put("keys", keys(await(keysFuture)));
        body.put("channels", channels(await(channelsFuture)));
        return body;
    }

    private Map<String, Object> all(String env, String range) {
        var costFuture = CompletableFuture.supplyAsync(() -> costs.cost(env, range), IO);
        var channelsFuture = CompletableFuture.supplyAsync(costs::channels, IO);
        var routesFuture = CompletableFuture.supplyAsync(costs::logicalRoutes, IO);
        var policyFuture = CompletableFuture.supplyAsync(costs::routePolicy, IO);
        var cost = await(costFuture);
        var body = new LinkedHashMap<String, Object>();
        body.put("models", models(cost));
        body.put("totalCny", cost.totalCny());
        body.put("keys", keys(cost.keys()));
        body.put("pendingCount", cost.pendingCount());
        body.put("bills", bills(cost.bills()));
        var policy = await(policyFuture);
        if (policy != null) {
            body.put("routePolicy", Map.of("version", policy.version(), "objective", policy.objective()));
        }
        body.put("logicalRoutes", routes(await(routesFuture)));
        body.put("channels", channels(await(channelsFuture)));
        return body;
    }

    private static List<Map<String, Object>> models(CostService.Result cost) {
        return cost.stats().stream().map(model -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", model.name());
            row.put("provider", model.provider());
            row.put("role", model.role());
            row.put("calls", model.calls());
            row.put("p95", model.p95());
            row.put("errorRate", model.timeoutRate());
            row.put("costCny", model.costCny());
            row.put("status", model.status());
            row.put("priceConfigured", model.priceConfigured());
            row.put("priceVersion", model.priceVersion());
            row.put("inputCnyPerToken", model.inputCnyPerToken());
            row.put("outputCnyPerToken", model.outputCnyPerToken());
            row.put("cacheCnyPerToken", model.cacheCnyPerToken());
            row.put("embedding", model.embedding());
            row.put("health", model.health());
            row.put("drift", model.drift());
            return row;
        }).toList();
    }

    private static List<Map<String, Object>> keys(List<CostService.Key> keys) {
        return keys.stream().map(key -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("alias", key.alias());
            row.put("version", key.version());
            row.put("agent", key.agent());
            row.put("env", key.env());
            row.put("models", key.models());
            row.put("dailyBudgetCny", key.dailyBudgetCny());
            row.put("spentCny", key.spentCny());
            row.put("reservedCny", key.reservedCny());
            row.put("status", key.blocked() ? "BLOCKED" : "ACTIVE");
            row.put("rpm", key.rpm());
            row.put("tpm", key.tpm());
            row.put("concurrency", key.concurrency());
            row.put("shares", key.shares() == null ? List.of() : key.shares().stream().map(share -> Map.of(
                    "id", share.id(), "rpm", share.rpm())).toList());
            return row;
        }).toList();
    }

    private static List<Map<String, Object>> bills(List<LiteLlmClient.Spend> bills) {
        return bills.stream()
                .sorted((left, right) -> String.valueOf(right.ts()).compareTo(String.valueOf(left.ts())))
                .limit(50)
                .map(bill -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts", bill.ts());
                    row.put("requestId", bill.requestId());
                    row.put("alias", bill.alias());
                    row.put("agent", CostService.agentOf(bill.alias()));
                    row.put("model", bill.model());
                    row.put("inputTokens", bill.inputTokens());
                    row.put("outputTokens", bill.outputTokens());
                    row.put("costCny", bill.costCny());
                    row.put("reservedCny", bill.reservedCny());
                    row.put("status", bill.status());
                    row.put("attemptId", bill.attemptId());
                    row.put("traceId", bill.traceId());
                    row.put("priceVersion", bill.priceVersion());
                    row.put("routeVersion", bill.routeVersion());
                    row.put("cacheTokens", bill.cacheTokens());
                    return row;
                }).toList();
    }

    private static List<Map<String, Object>> routes(List<LiteLlmClient.LogicalRoute> routes) {
        return routes.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("logical", row.logical());
            item.put("condition", row.condition());
            item.put("env", row.env());
            item.put("primary", row.primary());
            item.put("secondary", row.secondary());
            item.put("primaryWeight", row.primaryWeight());
            item.put("fallback", row.fallback());
            item.put("gray", row.gray());
            return item;
        }).toList();
    }

    private static List<Map<String, Object>> channels(List<LiteLlmClient.Channel> channels) {
        return channels.stream().map(channel -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", channel.id());
            row.put("name", channel.name());
            row.put("model", channel.model());
            row.put("env", channel.env());
            row.put("host", channel.host());
            row.put("rpm", channel.rpm());
            row.put("builtin", channel.builtin());
            row.put("prod", channel.prod());
            row.put("breaker", channel.breaker());
            row.put("openUntil", channel.openUntil());
            row.put("bound", channel.bound());
            return row;
        }).toList();
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

    private record Cached(long until, Map<String, Object> body) {}
}
