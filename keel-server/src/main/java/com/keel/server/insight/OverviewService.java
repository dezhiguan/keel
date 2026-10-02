package com.keel.server.insight;

import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OverviewService {
    public record Overview(Kpi kpi, List<AgentSummary> agents, List<Object> alerts, List<Object> costByAgent) {}

    public record Kpi(Integer onlineAgents, Integer totalAgents, Integer bizCount, Integer devCount,
                      Long calls, Double callsTrendPct, Double modelCostCny, Double avgScore,
                      Double gateThreshold, Integer pendingApprovals) {}

    private final AgentRegistryService agentRegistryService;

    public OverviewService(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    /**
     * TODO(P1-14): env / range are accepted but ignored; calls, cost, score come from Langfuse and LiteLLM,
     * biz/dev counts need manifest category, alerts need reconcile_finding, pendingApprovals needs approval.
     */
    public Overview overview(String env, String range) {
        var agents = agentRegistryService.listAll().stream()
                .filter(a -> a.status() != AgentStatus.RETIRED)
                .toList();
        var online = (int) agents.stream().filter(a -> a.status() == AgentStatus.ONLINE).count();
        var kpi = new Kpi(online, agents.size(), null, null, null, null, null, null, null, null);
        return new Overview(kpi, agents, List.of(), List.of());
    }
}
