package com.keel.server.provisioning;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AgentJwksController {
    private final AgentKeys keys;

    public AgentJwksController(AgentKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/api/v1/agents/{name}/jwks.json")
    public Map<String, Object> jwks(@PathVariable String name) {
        return keys.jwks(name);
    }
}
