package com.keel.starter.web;

import com.keel.starter.manifest.AgentRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final AgentRegistry registry;

    public HealthController(AgentRegistry registry) {
        this.registry = registry;
    }

    public Map<String, String> health(HttpServletRequest request) {
        return Map.of("status", "ok", "agent", registry.resolve(request).name());
    }
}
