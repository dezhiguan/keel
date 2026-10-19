package com.keel.server.insight.controller;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.Audited;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import com.keel.server.devflow.DevflowService;
import com.keel.server.insight.AgentUsageService;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.insight.CostService;
import com.keel.server.insight.OverviewService;
import com.keel.server.insight.SharedServiceMonitor;
import com.keel.server.insight.TraceQueryService;
import com.keel.server.registry.service.AgentRegistryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insight")
public class InsightController {
    private final OverviewService overviewService;
    private final TraceQueryService traces;
    private final CostService costs;
    private final SharedServiceMonitor services;
    private final AgentRegistryService agents;
    private final AgentUsageService usage;
    private final DevflowService devflow;

    public InsightController(OverviewService overviewService, TraceQueryService traces, CostService costs,
                             SharedServiceMonitor services, AgentRegistryService agents, AgentUsageService usage,
                             DevflowService devflow) {
        this.overviewService = overviewService;
        this.traces = traces;
        this.costs = costs;
        this.services = services;
        this.agents = agents;
        this.usage = usage;
        this.devflow = devflow;
    }

    @GetMapping("/overview")
    public R<OverviewService.Overview> overview(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "today|24h|7d|30d|all") String range) {
        return R.ok(overviewService.overview(env, range));
    }

    @GetMapping("/agent-usage")
    public R<Map<String, Object>> agentUsage(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env) {
        var scope = env == null || env.isBlank() ? "all" : env;
        var listed = agents.listAll().stream()
                .filter(agent -> "all".equals(scope) || scope.equals(agent.env()))
                .toList();
        return R.ok(Map.of("items", usage.apply(listed, scope)));
    }

    @GetMapping("/traces")
    public R<Map<String, Object>> traces(@RequestParam(defaultValue = "1") @Min(1) int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(required = false) String agent,
                                         @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
                                         @RequestParam(required = false) @Pattern(regexp = "ok|fallback|failed|blocked|pending") String status,
                                         @RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to,
                                         @RequestParam(defaultValue = "false") boolean multi,
                                         @RequestParam(required = false) Integer minDurationMs,
                                         @RequestParam(required = false) String jobId) {
        return R.ok(traces.list(page, size, agent == null ? "" : agent, env, status, from, to, multi, minDurationMs, jobId));
    }

    @GetMapping("/costs")
    public R<Map<String, Object>> costs(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range) {
        var cost = costs.cost(env, range);
        var models = cost.stats().stream().map(model -> {
            var row = new LinkedHashMap<String, Object>();
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
        var keys = cost.keys().stream()
                .map(key -> {
                    var row = new LinkedHashMap<String, Object>();
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
                    return row;
                }).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("models", models);
        body.put("totalCny", cost.totalCny());
        body.put("keys", keys);
        body.put("pendingCount", cost.pendingCount());
        body.put("bills", cost.bills().stream().sorted((left, right) -> right.ts().compareTo(left.ts()))
                .limit(50).map(bill -> {
                    var row = new LinkedHashMap<String, Object>();
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
                }).toList());
        var policy = costs.routePolicy();
        if (policy != null) {
            body.put("routePolicy", java.util.Map.of("version", policy.version(), "objective", policy.objective()));
        }
        body.put("logicalRoutes", costs.logicalRoutes().stream().map(row -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("logical", row.logical());
            item.put("condition", row.condition());
            item.put("env", row.env());
            item.put("primary", row.primary());
            item.put("secondary", row.secondary());
            item.put("primaryWeight", row.primaryWeight());
            item.put("fallback", row.fallback());
            item.put("gray", row.gray());
            return item;
        }).toList());
        body.put("channels", costs.channels().stream().map(channel -> {
            var row = new LinkedHashMap<String, Object>();
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
        }).toList());
        body.put("alertRules", costs.alertRules().stream().map(rule -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", rule.id());
            row.put("name", rule.name());
            row.put("metric", rule.metric());
            row.put("threshold", rule.threshold());
            row.put("window", rule.window());
            row.put("groupBy", rule.groupBy());
            return row;
        }).toList());
        var devflowCost = devflow.cost();
        if (devflowCost != null) {
            body.put("devflowCost", devflowCost);
        }
        return R.ok(body);
    }

    @GetMapping(value = "/costs/export", produces = "text/csv")
    public ResponseEntity<String> exportCosts(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range) {
        var csv = new StringBuilder("ts,attemptId,requestId,alias,model,status,inputTokens,outputTokens,cacheTokens,costCny,priceVersion,routeVersion,traceId\n");
        for (var bill : costs.cost(env, range).bills()) {
            csv.append(csvCell(bill.ts())).append(',')
                    .append(csvCell(bill.attemptId())).append(',')
                    .append(csvCell(bill.requestId())).append(',')
                    .append(csvCell(bill.alias())).append(',')
                    .append(csvCell(bill.model())).append(',')
                    .append(csvCell(bill.status())).append(',')
                    .append(bill.inputTokens() == null ? "" : bill.inputTokens()).append(',')
                    .append(bill.outputTokens() == null ? "" : bill.outputTokens()).append(',')
                    .append(bill.cacheTokens() == null ? "" : bill.cacheTokens()).append(',')
                    .append(bill.costCny() == null ? "" : bill.costCny()).append(',')
                    .append(csvCell(bill.priceVersion())).append(',')
                    .append(csvCell(bill.routeVersion())).append(',')
                    .append(csvCell(bill.traceId())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bills.csv")
                .body(csv.toString());
    }

    @PostMapping("/models/{name}/probe")
    public R<Map<String, String>> probe(@PathVariable String name) {
        costs.probe(name);
        return R.ok(Map.of("name", name, "health", "PROBED"));
    }

    @PostMapping("/alert-rules")
    public R<java.util.Map<String, Object>> addAlert(@RequestBody java.util.Map<String, Object> body) {
        var threshold = body.get("threshold") == null ? -1d : Double.parseDouble(body.get("threshold").toString());
        var rule = costs.addAlert(String.valueOf(body.get("name")), String.valueOf(body.get("metric")), threshold,
                String.valueOf(body.getOrDefault("window", "")), String.valueOf(body.getOrDefault("groupBy", "")));
        return R.ok(java.util.Map.of("id", rule.id(), "name", rule.name()));
    }

    @PostMapping("/costs/keys/{alias}/budget")
    @Audited
    public R<Map<String, Object>> updateBudget(@PathVariable String alias, @RequestBody Map<String, Object> body) {
        var raw = body.get("dailyBudgetCny");
        if (raw == null) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "dailyBudgetCny 缺失");
        }
        var budget = new BigDecimal(raw.toString());
        costs.updateBudget(alias, budget);
        return R.ok(Map.of("alias", alias, "dailyBudgetCny", budget));
    }

    @PostMapping("/route-policy")
    @Audited
    public R<Map<String, Object>> publishRoute(@RequestBody Map<String, Object> body) {
        requireAdmin();
        var policy = costs.publishRoute(body.get("objective") == null ? "" : body.get("objective").toString());
        return R.ok(Map.of("version", policy.version(), "objective", policy.objective()));
    }

    @PostMapping("/route-policy/rollback")
    @Audited
    public R<Map<String, Object>> rollbackRoute() {
        requireAdmin();
        var policy = costs.rollbackRoute();
        return R.ok(Map.of("version", policy.version(), "objective", policy.objective()));
    }

    @PostMapping("/channels")
    @Audited
    public R<Map<String, Object>> addChannel(@RequestBody Map<String, Object> body) {
        requireAdmin();
        int rpm;
        try {
            rpm = body.get("rpm") == null ? 0 : Integer.parseInt(body.get("rpm").toString());
        } catch (NumberFormatException error) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "厂商上限 RPM 必须大于 0");
        }
        var created = costs.addChannel(text(body.get("name")), text(body.get("model")), text(body.get("env")), rpm);
        return R.ok(channelBody(created));
    }

    @PostMapping("/channels/switch")
    @Audited
    public R<Map<String, Object>> switchChannel(@RequestBody Map<String, Object> body) {
        requireAdmin();
        var moved = costs.switchChannel(text(body.get("id")));
        return R.ok(Map.of("moved", moved.moved(), "name", moved.name(), "id", moved.id()));
    }

    @PostMapping("/channels/simulate")
    @Audited
    public R<Map<String, Object>> simulateChannel(@RequestBody Map<String, Object> body) {
        requireAdmin();
        var result = costs.simulateChannel(text(body.get("id")));
        return R.ok(Map.of("id", result.id(), "breaker", result.breaker(), "openUntil", result.openUntil()));
    }

    @PostMapping("/channels/probe")
    @Audited
    public R<Map<String, Object>> probeChannel(@RequestBody Map<String, Object> body) {
        requireAdmin();
        var result = costs.probeChannel(text(body.get("id")));
        return R.ok(Map.of("id", result.id(), "breaker", result.breaker(), "openUntil", result.openUntil()));
    }

    @PostMapping("/route/preview")
    public R<Map<String, Object>> previewRoute(@RequestBody Map<String, Object> body) {
        var preview = costs.previewRoute(
                body.get("alias") == null ? "" : body.get("alias").toString(),
                body.get("model") == null ? "" : body.get("model").toString());
        return R.ok(Map.of("version", preview.version(), "objective", preview.objective(), "models", preview.models()));
    }

    private static Map<String, Object> channelBody(LiteLlmClient.Channel channel) {
        var row = new LinkedHashMap<String, Object>();
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
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void requireAdmin() {
        var principal = ConsolePrincipal.current();
        if (principal == null || principal.readOnly() || !"ADMIN".equals(principal.platformRole())) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
    }

    private static String csvCell(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @GetMapping("/services")
    public R<Map<String, Object>> services(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env) {
        return R.ok(services.services(env));
    }

    @GetMapping("/traces/{traceId}")
    public R<Map<String, Object>> trace(@PathVariable String traceId) {
        return R.ok(traces.detail(traceId));
    }
}
