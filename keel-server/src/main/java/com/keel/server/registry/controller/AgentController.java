package com.keel.server.registry.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.PageResult;
import com.keel.server.common.R;
import com.keel.server.insight.AgentUsageService;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.registry.service.AgentChatService;
import com.keel.server.registry.service.AgentRegistryService;
import com.keel.server.registry.service.LifecycleService;
import com.keel.server.registry.service.SelfCheckService;
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
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentRegistryService agentRegistryService;
    private final LifecycleService lifecycleService;
    private final AgentChatService chats;
    private final AgentUsageService usage;

    public AgentController(AgentRegistryService agentRegistryService, LifecycleService lifecycleService,
                           AgentChatService chats, AgentUsageService usage) {
        this.agentRegistryService = agentRegistryService;
        this.lifecycleService = lifecycleService;
        this.chats = chats;
        this.usage = usage;
    }

    @GetMapping
    public R<PageResult<AgentSummary>> list(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|biz|dev") String category,
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean usage,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Pattern(regexp = "10|20|50|100") String size) {
        var pageResult = agentRegistryService.page(env, category, status, q, page, Integer.parseInt(size));
        var items = usage ? this.usage.apply(pageResult.items(), env) : pageResult.items();
        return R.ok(new PageResult<>(pageResult.page(), pageResult.size(), pageResult.total(), items));
    }

    @PostMapping
    public R<SelfCheckService.Report> register(@RequestBody JsonNode body) {
        return R.ok(lifecycleService.register(body));
    }

    @GetMapping("/name-check")
    public R<java.util.Map<String, Object>> nameCheck(@RequestParam String name) {
        return R.ok(agentRegistryService.nameCheck(name));
    }

    @PostMapping("/manifest-preview")
    public R<java.util.Map<String, Object>> preview(@RequestBody JsonNode form) {
        var rendered = agentRegistryService.preview(form);
        return R.ok(java.util.Map.of("yaml", rendered.yaml(), "warnings", rendered.warnings()));
    }

    @GetMapping("/{name}")
    public R<AgentDetail> detail(@PathVariable String name) {
        return R.ok(agentRegistryService.detail(name));
    }

    @PostMapping("/{name}/chat")
    public R<java.util.Map<String, Object>> chat(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(chats.chat(name, body.path("text").asText("")));
    }
}
