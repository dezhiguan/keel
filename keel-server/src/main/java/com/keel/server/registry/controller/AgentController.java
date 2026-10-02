package com.keel.server.registry.controller;

import com.keel.server.common.PageResult;
import com.keel.server.common.R;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.registry.service.AgentRegistryService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentRegistryService agentRegistryService;

    public AgentController(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    @GetMapping
    public R<PageResult<AgentSummary>> list(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|staging|prod") String env,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|biz|dev") String category,
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Pattern(regexp = "10|20|50|100") String size) {
        return R.ok(agentRegistryService.page(status, q, page, Integer.parseInt(size)));
    }
}
