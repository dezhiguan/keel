package com.keel.server.insight.controller;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.Audited;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import com.keel.server.insight.CostService;
import com.keel.server.insight.OverviewService;
import com.keel.server.insight.SharedServiceMonitor;
import com.keel.server.insight.TraceQueryService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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

    public InsightController(OverviewService overviewService, TraceQueryService traces, CostService costs, SharedServiceMonitor services) {
        this.overviewService = overviewService;
        this.traces = traces;
        this.costs = costs;
        this.services = services;
    }

    @GetMapping("/overview")
    public R<OverviewService.Overview> overview(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range) {
        return R.ok(overviewService.overview(env, range));
    }

    @GetMapping("/traces")
    public R<Map<String, Object>> traces(@RequestParam(defaultValue = "1") @Min(1) int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(required = false) String agent,
                                         @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env) {
        return R.ok(traces.list(page, size, agent == null ? "" : agent, env));
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
            return row;
        }).toList();
        var keys = cost.keys().stream()
                .map(key -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("alias", key.alias());
                    row.put("agent", key.agent());
                    row.put("env", key.env());
                    row.put("models", key.models());
                    row.put("dailyBudgetCny", key.dailyBudgetCny());
                    row.put("spentCny", key.spentCny());
                    row.put("status", key.blocked() ? "BLOCKED" : "ACTIVE");
                    return row;
                }).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("models", models);
        body.put("totalCny", cost.totalCny());
        body.put("keys", keys);
        return R.ok(body);
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
