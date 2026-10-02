package com.keel.server.registry.model.dto;

import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.enums.AgentStatus;

/** contracts/console-api.openapi.yaml#/components/schemas/AgentSummary. */
public record AgentSummary(
        String name,
        String displayName,
        String category,
        String language,
        String runtime,
        String template,
        String env,
        String version,
        AgentStatus status,
        String statusNote,
        Boolean gatePassed,
        String ownerOrg,
        String ownerUser,
        Long calls24h,
        Long callsTotal,
        Double p95Seconds,
        Double costCny,
        Double dailyBudgetCny,
        Double score,
        String instances,
        Boolean multiAgent) {

    /**
     * TODO(P1-4): category / template / multiAgent / dailyBudgetCny come from the manifest in agent_version,
     * env / version from the latest agent_version, instances from agent_instance; none is populated yet.
     * TODO(P1-14): calls / p95 / cost / score come from insight (Langfuse, LiteLLM).
     */
    public static AgentSummary of(Agent agent) {
        return new AgentSummary(agent.getName(), agent.getDisplayName(), null, agent.getLanguage(),
                agent.getRuntime(), null, null, null, agent.getStatus(), null, null,
                agent.getOwnerOrg(), agent.getOwnerUser(), null, null, null, null, null, null, null, null);
    }
}
