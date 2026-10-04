package com.keel.server.discovery;

import com.keel.server.integration.agent.AgentEndpointClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Writes the running version onto the instance. A failed read keeps the previous version. */
public class ManifestVersionChecker {
    private static final Logger log = LoggerFactory.getLogger(ManifestVersionChecker.class);

    public String nextVersion(String agent, String current, String endpoint, AgentEndpointClient client) {
        try {
            var remote = client.manifestVersion(endpoint);
            if (remote == null || remote.isBlank()) {
                return current;
            }
            return remote;
        } catch (RuntimeException e) {
            log.warn("trace_id={} agent={} manifest 核对失败", MDC.get("trace_id"), agent);
            return current;
        }
    }
}
