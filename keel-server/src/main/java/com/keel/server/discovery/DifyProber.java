package com.keel.server.discovery;

import com.keel.server.integration.agent.AgentEndpointClient;

import java.time.Instant;

/** Probes the registered endpoint of a Dify agent. Code agents are skipped. */
public class DifyProber {
    private final AgentEndpointClient client;

    public DifyProber(AgentEndpointClient client) {
        this.client = client;
    }

    public void probe(String runtime, String agent, String env, String instanceId, String endpoint, InstanceBook instances, Instant now) {
        if (!"dify".equals(runtime)) {
            return;
        }
        var up = false;
        try {
            up = client.healthy(endpoint);
        } catch (RuntimeException ignored) {
            up = false;
        }
        instances.probe(agent, env, instanceId, up, now);
    }
}
