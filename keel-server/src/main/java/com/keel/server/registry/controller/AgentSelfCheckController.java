package com.keel.server.registry.controller;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import com.keel.server.registry.service.*;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentSelfCheckController {
    private final AgentRegistryService registry;
    private final SelfCheckService checks;
    private final AuditStore audits;
    public AgentSelfCheckController(AgentRegistryService registry, SelfCheckService checks, AuditStore audits) {
        this.registry = registry; this.checks = checks; this.audits = audits;
    }
    @PostMapping("/{name}/self-check")
    public R<SelfCheckService.Report> check(@PathVariable String name) {
        var detail = registry.detail(name);
        if (detail.summary().status() == AgentStatus.RETIRED || detail.summary().status() == AgentStatus.DRAFT
                || detail.resources().isEmpty() || detail.resources().stream().anyMatch(r -> !"ACTIVE".equals(r.status()))) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, "智能体尚未开通或已下线");
        }
        var report = checks.checkRegistered(name, detail.summary().env(), registry.invokeEndpoint(name), registry.manifestOrEmpty(name));
        audits.append(name, detail.summary().env(), "agent.self-check", "low", "allowed", name, null);
        return R.ok(report);
    }
}
