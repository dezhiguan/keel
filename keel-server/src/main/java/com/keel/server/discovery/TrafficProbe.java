package com.keel.server.discovery;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;

import java.time.Duration;
import java.time.Instant;

/**
 * Gateway spend is read on every reconcile. Langfuse metrics are read at most once a day
 * because Hobby allows 100 metrics calls per day. A positive project-wide count does not
 * identify the agent, so it stays unknown instead of marking every agent active.
 */
public class TrafficProbe {
    private static final Duration LANGFUSE_INTERVAL = Duration.ofHours(24);
    private final LangfuseClient langfuse;
    private final LiteLlmClient gateway;
    private Instant langfuseCheckedAt;
    private Boolean idleProject;

    public TrafficProbe(LangfuseClient langfuse, LiteLlmClient gateway) {
        this.langfuse = langfuse;
        this.gateway = gateway;
    }

    public Boolean langfuse(Instant now) {
        if (langfuseCheckedAt != null && langfuseCheckedAt.plus(LANGFUSE_INTERVAL).isAfter(now)) {
            return idleProject;
        }
        var count = langfuse.observationCount(now.minus(ReconcileRules.IDLE), now);
        if (count == null) {
            return null;
        }
        langfuseCheckedAt = now;
        idleProject = count == 0 ? Boolean.FALSE : null;
        return idleProject;
    }

    public Boolean gateway(String agent, String env, Instant now) {
        return gateway.calledSince(agent + "-" + env, now.minus(ReconcileRules.IDLE));
    }
}
