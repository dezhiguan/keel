package com.keel.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Gateway {
    public static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9-]{1,38}[a-z0-9]-(dev|test|staging|prod)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ModelCatalog catalog;
    private final BudgetCounter budget;
    private final Upstream upstream;
    private final KeyDirectory keys;
    private final SpendLog spendLog;

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream) {
        this(catalog, budget, upstream, new KeyDirectory());
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys) {
        this(catalog, budget, upstream, keys, new SpendLog(null));
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog) {
        this.catalog = catalog;
        this.budget = budget;
        this.upstream = upstream;
        this.keys = keys;
        this.spendLog = spendLog;
    }

    public synchronized Created create(String alias, List<String> models, List<String> fallback, BigDecimal dailyBudgetCny, boolean allowFallback) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "别名必须是 {agent}-{env}");
        }
        if (dailyBudgetCny == null || dailyBudgetCny.signum() <= 0) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "dailyBudgetCny 必须大于 0");
        }
        models.forEach(catalog::require);
        var token = "sk-keel-" + UUID.randomUUID().toString().replace("-", "");
        var key = new VirtualKey(alias, token, List.copyOf(models), List.copyOf(fallback), dailyBudgetCny, allowFallback, false);
        keys.save(key);
        return new Created(alias, token);
    }

    public synchronized void block(String alias) {
        var key = keys.byAlias(alias);
        if (key == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
        }
        keys.save(key.asBlocked());
    }

    public synchronized void updateBudget(String alias, BigDecimal dailyBudgetCny) {
        if (dailyBudgetCny == null || dailyBudgetCny.signum() <= 0) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "dailyBudgetCny 必须大于 0");
        }
        var key = keys.byAlias(alias);
        if (key == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
        }
        keys.save(key.withBudget(dailyBudgetCny));
    }

    public List<ListedKey> listed() {
        return keys.list().stream()
                .map(key -> new ListedKey(key.alias(), key.models(), key.dailyBudgetCny(), budget.spent(key.alias()), key.blocked()))
                .toList();
    }

    public synchronized VirtualKey requireAlias(String alias) {
        var key = keys.byAlias(alias);
        if (key == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
        }
        return key;
    }

    public List<Spend> spend(String alias, int page, int size) {
        var matched = spendLog.all().stream().filter(row -> alias == null || alias.isBlank() || row.alias.equals(alias)).toList();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(matched.size(), from + size);
        return from >= matched.size() ? List.of() : matched.subList(from, to);
    }

    public Completion complete(String bearer, String path, String body, Duration timeout) {
        var key = virtualKey(bearer);
        var request = read(body);
        var requested = request.path("model").asText("");
        if (!key.models.contains(requested)) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "模型不在该 Key 的允许列表里");
        }
        if (budget.spent(key.alias).compareTo(key.dailyBudgetCny) >= 0) {
            throw new GatewayException(ErrorCode.LLM_BUDGET_EXCEEDED, ErrorCode.LLM_BUDGET_EXCEEDED.message());
        }
        var attempts = attempts(key, requested, "/v1/embeddings".equals(path));
        Upstream.Result result = null;
        String used = requested;
        long latencyMs = 0;
        for (int i = 0; i < attempts.size(); i++) {
            used = attempts.get(i);
            var model = catalog.require(used);
            var forwarded = ((ObjectNode) request.deepCopy()).put("model", used).toString();
            var started = System.nanoTime();
            result = upstream.post(model.upstream(), model.upstreamKey(), path, forwarded, timeout);
            latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            if (result.retryable()) {
                remember(key.alias(), used, 0, 0, BigDecimal.ZERO, latencyMs, result.status() == 504);
                if (i < attempts.size() - 1) {
                    continue;
                }
                throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "上游失败");
            }
            break;
        }
        if (result == null) {
            throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "上游失败");
        }
        var cost = costOf(used, result.body());
        budget.add(key.alias, cost);
        var row = remember(key.alias, used, tokens(result.body(), "prompt_tokens"), tokens(result.body(), "completion_tokens"), cost, latencyMs, false);
        return new Completion(result.status(), result.body(), row);
    }

    private Spend remember(String alias, String model, int inputTokens, int outputTokens, BigDecimal cost, long latencyMs, boolean timedOut) {
        var row = new Spend(alias, model, inputTokens, outputTokens, cost, "req-" + UUID.randomUUID(), Instant.now(), latencyMs, timedOut);
        spendLog.append(row);
        return row;
    }

    private List<String> attempts(VirtualKey key, String requested, boolean embedding) {
        if (embedding || !key.allowFallback || key.fallback.isEmpty()) {
            return List.of(requested);
        }
        var next = key.fallback.get(0);
        if (next.equals(requested)) {
            return List.of(requested);
        }
        return List.of(requested, next);
    }

    private VirtualKey virtualKey(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ")) {
            throw new GatewayException(ErrorCode.LLM_KEY_BLOCKED, ErrorCode.LLM_KEY_BLOCKED.message());
        }
        VirtualKey key;
        synchronized (this) {
            key = keys.byToken(bearer.substring("Bearer ".length()).trim());
        }
        if (key == null || key.blocked) {
            throw new GatewayException(ErrorCode.LLM_KEY_BLOCKED, ErrorCode.LLM_KEY_BLOCKED.message());
        }
        return key;
    }

    private BigDecimal costOf(String modelName, String body) {
        var model = catalog.require(modelName);
        var node = read(body);
        var input = BigDecimal.valueOf(tokens(body, "prompt_tokens")).multiply(model.inputCnyPerToken());
        var output = BigDecimal.valueOf(tokens(node, "completion_tokens")).multiply(model.outputCnyPerToken());
        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }

    private int tokens(String body, String field) {
        return tokens(read(body), field);
    }

    private int tokens(JsonNode node, String field) {
        return node.path("usage").path(field).asInt(0);
    }

    private JsonNode read(String body) {
        try {
            return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "请求体不是 JSON");
        }
    }

    public record Created(String alias, String key) {}

    public record VirtualKey(String alias, String token, List<String> models, List<String> fallback,
                             BigDecimal dailyBudgetCny, boolean allowFallback, boolean blocked) {
        public VirtualKey asBlocked() {
            return new VirtualKey(alias, token, models, fallback, dailyBudgetCny, allowFallback, true);
        }

        public VirtualKey withBudget(BigDecimal budget) {
            return new VirtualKey(alias, token, models, fallback, budget, allowFallback, blocked);
        }
    }

    public record ListedKey(String alias, List<String> models, BigDecimal dailyBudgetCny, BigDecimal spentCny, boolean blocked) {}

    public record Spend(String alias, String model, int inputTokens, int outputTokens, BigDecimal costCny, String requestId, Instant ts, long latencyMs, boolean timedOut) {}

    public record Completion(int status, String body, Spend spend) {}

    public static final class GatewayException extends RuntimeException {
        private final ErrorCode code;

        public GatewayException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }
}
