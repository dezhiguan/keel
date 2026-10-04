package com.keel.server.discovery;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/instances")
public class HeartbeatController {
    private final InstanceBook instances;

    public HeartbeatController(InstanceBook instances) {
        this.instances = instances;
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@RequestBody Beat beat) {
        instances.beat(beat.agent(), beat.env(), beat.instanceId(), beat.version(), Instant.now());
    }

    @Scheduled(fixedRate = 15_000, initialDelay = 15_000)
    public void expire() {
        instances.expire(Instant.now());
    }

    public record Beat(String agent, String env, String instanceId, String version) {}
}
