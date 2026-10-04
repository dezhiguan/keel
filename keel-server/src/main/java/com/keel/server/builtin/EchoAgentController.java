package com.keel.server.builtin;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/builtin/agents/{name}")
public class EchoAgentController {
    private final EchoProbe probe;

    public EchoAgentController(EchoProbe probe) {
        this.probe = probe;
    }

    @GetMapping("/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping(value = "/v1/invoke", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public String invoke(@PathVariable String name) {
        return probe.invoke(name, "dev");
    }
}
