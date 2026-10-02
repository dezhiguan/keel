package com.keel.server.insight.controller;

import com.keel.server.common.R;
import com.keel.server.insight.OverviewService;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insight")
public class InsightController {
    private final OverviewService overviewService;

    public InsightController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    public R<OverviewService.Overview> overview(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|staging|prod") String env,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = "24h|7d|30d") String range) {
        return R.ok(overviewService.overview(env, range));
    }
}
