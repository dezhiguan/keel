package com.keel.server.insight.controller;

import com.keel.server.common.R;
import com.keel.server.insight.OverviewService;
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

    public InsightController(OverviewService overviewService, TraceQueryService traces) {
        this.overviewService = overviewService;
        this.traces = traces;
    }

    @GetMapping("/overview")
    public R<OverviewService.Overview> overview(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range) {
        return R.ok(overviewService.overview(env, range));
    }

    @GetMapping("/traces")
    public R<Map<String, Object>> traces(@RequestParam(defaultValue = "1") @Min(1) int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return R.ok(traces.list(page, size));
    }

    @GetMapping("/traces/{traceId}")
    public R<Map<String, Object>> trace(@PathVariable String traceId) {
        return R.ok(traces.detail(traceId));
    }
}
