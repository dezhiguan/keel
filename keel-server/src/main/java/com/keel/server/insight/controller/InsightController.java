package com.keel.server.insight.controller;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.Audited;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import com.keel.server.insight.AgentUsageService;
import com.keel.server.insight.ModelGatewayView;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.insight.CostService;
import com.keel.server.insight.OverviewService;
import com.keel.server.insight.SharedServiceMonitor;
import com.keel.server.insight.TraceQueryService;
import com.keel.server.registry.service.AgentRegistryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    @org.springframework.beans.factory.annotation.Autowired private com.keel.server.insight.OverviewReadModel overviewReadModel;
    private final OverviewService overviewService;
    private final TraceQueryService traces;
    private final CostService costs;
    private final SharedServiceMonitor services;
    private final AgentRegistryService agents;
    private final AgentUsageService usage;
    private final ModelGatewayView gatewayView;

    public InsightController(OverviewService overviewService, TraceQueryService traces, CostService costs,
                             SharedServiceMonitor services, AgentRegistryService agents, AgentUsageService usage) {
        this.overviewService = overviewService;
        this.traces = traces;
        this.costs = costs;
        this.services = services;
        this.agents = agents;
        this.usage = usage;
        this.gatewayView = new ModelGatewayView(costs);
    }

    @GetMapping("/overview")
    public R<OverviewService.Overview> overview(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "today|24h|7d|30d|all") String range) {
        return R.ok(overviewReadModel.read(env, range));
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
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|channels|keys|routes") String section) {
        return R.ok(gatewayView.get(env, range, section));
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
        BigDecimal budget = null;
        if (raw != null) {
            budget = new BigDecimal(raw.toString());
        }
        var limits = new LinkedHashMap<String, Integer>();
        for (var field : java.util.List.of("rpm", "tpm", "concurrency")) {
            if (body.containsKey(field)) {
                requireAdmin();
                try {
                    limits.put(field, body.get(field) == null ? null : new BigDecimal(body.get(field).toString()).intValueExact());
                } catch (NumberFormatException | ArithmeticException error) {
                    throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "RPM、TPM、并发须为正整数");
                }
            }
        }
        List<LiteLlmClient.Share> channels = null;
        if (body.containsKey("channels")) {
            requireAdmin();
            channels = channelShares(body.get("channels"));
        }
        if (budget == null && channels == null) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "dailyBudgetCny 缺失");
        }
        costs.updateBudget(alias, budget, limits, channels);
        var saved = new LinkedHashMap<String, Object>();
        saved.put("alias", alias);
        if (budget != null) {
            saved.put("dailyBudgetCny", budget);
        }
        return R.ok(saved);
    }

    @GetMapping("/route-table")
    public R<com.fasterxml.jackson.databind.JsonNode> routeTable() {
        return R.ok(costs.logicalRouteTable(null,Map.of()));
    }

    @PostMapping({"/route-table", "/route-table/{operation}"})
    @Audited
    public R<com.fasterxml.jackson.databind.JsonNode> routeTableWrite(@PathVariable(required=false) String operation,@RequestBody Map<String,Object> body) {
        requireAdmin();
        if(operation!=null && !java.util.Set.of("publish","rollback","grants","replay").contains(operation))throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"未知路由操作");
        return R.ok(costs.logicalRouteTable(operation==null?"":operation,body));
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
        var env = text(body.get("env"));
        if (!java.util.Set.of("staging", "prod").contains(env)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "渠道环境只能是 staging 或 prod");
        }
        var created = costs.addChannel(text(body.get("name")), text(body.get("model")), env, rpm);
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

    private static List<LiteLlmClient.Share> channelShares(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "至少允许一个渠道");
        }
        var shares = new ArrayList<LiteLlmClient.Share>();
        try {
            for (var item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "渠道份额格式不正确");
                }
                var rpm = map.get("rpm");
                shares.add(new LiteLlmClient.Share(map.get("id") == null ? "" : map.get("id").toString(),
                        rpm == null ? 0 : new BigDecimal(rpm.toString()).intValueExact()));
            }
        } catch (NumberFormatException | ArithmeticException error) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "渠道 RPM 须为正整数");
        }
        return shares;
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
