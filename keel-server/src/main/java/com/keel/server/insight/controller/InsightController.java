package com.keel.server.insight.controller;

import com.keel.server.common.R;
import com.keel.server.insight.CostService;
import com.keel.server.insight.OverviewService;
import com.keel.server.insight.SharedServiceMonitor;
import com.keel.server.insight.TraceQueryService;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range) {
        return R.ok(overviewService.overview(env, range));
    }

    @GetMapping("/traces")
    public R<Map<String, Object>> traces(@RequestParam(defaultValue = "1") @Min(1) int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(required = false) String agent) {
        return R.ok(traces.list(page, size, agent == null ? "" : agent));
    }

    @GetMapping("/costs")
    public R<Map<String, Object>> costs(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range) {
        var cost = costs.cost();
        var models = cost.models().stream().map(model -> Map.of(
                "model", model.name(),
                "costCny", cost.byModel().getOrDefault(model.name(), 0d),
                "priceConfigured", model.priceConfigured())).toList();
        return R.ok(Map.of("models", models, "totalCny", cost.totalCny()));
    }

    @GetMapping("/services")
    public R<Map<String, Object>> services() {
        return R.ok(services.services());
    }

    @GetMapping("/traces/{traceId}")
    public R<Map<String, Object>> trace(@PathVariable String traceId) {
        return R.ok(traces.detail(traceId));
    }
}
