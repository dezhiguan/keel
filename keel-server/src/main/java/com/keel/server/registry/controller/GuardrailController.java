package com.keel.server.registry.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.Audited;
import com.keel.server.common.R;
import com.keel.server.registry.service.GuardrailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class GuardrailController {
    private final GuardrailService guards;

    public GuardrailController(GuardrailService guards) {
        this.guards = guards;
    }

    @GetMapping("/api/v1/agents/{name}/guardrails")
    public R<Map<String, Object>> page(@PathVariable String name) {
        return R.ok(guards.page(name));
    }

    @PutMapping("/api/v1/agents/{name}/guardrails")
    @Audited
    public R<Map<String, Object>> save(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(guards.save(name, body));
    }

    /** Read-only rule check. Preview sessions can call it; it does not invoke the agent. */
    @GetMapping("/api/v1/agents/{name}/guard/check")
    public R<Map<String, Object>> check(@PathVariable String name, @RequestParam String source,
                                         @RequestParam(defaultValue = "") String text) {
        return R.ok(guards.checkTimed(name, source, text));
    }
}
