package com.keel.server.insight;

import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class OverviewService {
    public record Overview(Kpi kpi, List<AgentSummary> agents, List<?> alerts, List<Map<String, Object>> costByAgent) {}

    public record Kpi(Integer onlineAgents, Integer totalAgents, Integer bizCount, Integer devCount,
                      Long calls, Double callsTrendPct, Double modelCostCny, Double avgScore,
                      Double gateThreshold, Integer pendingApprovals) {}

    private final AgentRegistryService agentRegistryService;
    private final CostService costs;
    private final QualityService quality;

    public OverviewService(AgentRegistryService agentRegistryService, CostService costs, QualityService quality) {
        this.agentRegistryService = agentRegistryService;
        this.costs = costs;
        this.quality = quality;
    }

    public Overview overview(String env, String range) {
        var agents = agentRegistryService.listAll().stream()
                .filter(a -> a.status() != AgentStatus.RETIRED)
                .filter(a -> "all".equals(env) || env.equals(a.env()))
                .toList();
        var online = (int) agents.stream().filter(a -> a.status() == AgentStatus.ONLINE).count();
        var cost = costs.cost();
        var byAgent = cost.byAgent().entrySet().stream()
                .map(entry -> Map.<String, Object>of("agent", entry.getKey(), "costCny", entry.getValue()))
                .toList();
        // TODO(P3-1): pendingApprovals stays 0 until the approval table is queryable.
        var kpi = new Kpi(online, agents.size(), null, null, (long) cost.calls(), null, cost.totalCny(), quality.average(), null, 0);
        return new Overview(kpi, agents, List.of(), byAgent);
    }
}
