package com.keel.starter.web;

import com.keel.common.model.AgentManifest;
import com.keel.starter.manifest.AgentBinding;
import com.keel.starter.manifest.AgentRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManifestController {
    private final AgentRegistry registry;

    public ManifestController(AgentRegistry registry) {
        this.registry = registry;
    }

    public Map<String, Object> manifest(HttpServletRequest request) {
        AgentBinding binding = registry.resolve(request);
        AgentManifest manifest = binding.manifest();
        return Map.of(
                "manifest", manifest,
                "version", manifest.apiVersion().value(),
                "sdkVersion", "0.1.0");
    }
}
