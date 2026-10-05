package com.keel.server.insight;

import com.keel.server.discovery.FindingBook;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;

@Service
public class OverviewService {
    public static final double GATE = 0.85;

    public record Overview(Kpi kpi, List<AgentSummary> agents, List<Alert> alerts, List<Map<String, Object>> costByAgent) {}

    public record Kpi(Integer onlineAgents, Integer totalAgents, Integer bizCount, Integer devCount,
                      Long calls, Double callsTrendPct, Double modelCostCny, Double avgScore,
                      Double gateThreshold, Integer pendingApprovals) {}

    public record Alert(String at, String level, String agent, String kind, String text, String link) {}

    private final AgentRegistryService agentRegistryService;
    private final AgentUsageService usage;
    private final QualityService quality;
    private final IntSupplier pendingApprovals;
    private final FindingBook findings;

    @Autowired
    public OverviewService(AgentRegistryService agentRegistryService, AgentUsageService usage, QualityService quality,
                           IntSupplier pendingApprovals, FindingBook findings) {
        this.agentRegistryService = agentRegistryService;
        this.usage = usage;
        this.quality = quality;
        this.pendingApprovals = pendingApprovals;
        this.findings = findings;
    }

    /** Spend-ledger tests construct the service without a usage cache or finding book. */
    public OverviewService(AgentRegistryService agentRegistryService, CostService costs, QualityService quality,
                           IntSupplier pendingApprovals) {
        this(agentRegistryService,
                new AgentUsageService(new com.keel.server.integration.langfuse.LangfuseClient("", "", ""),
                        costsGateway(costs), quality, com.keel.server.insight.SavedTraces.EMPTY),
                quality, pendingApprovals, new FindingBook(null));
    }

    public Overview overview(String env, String range) {
        var scope = env == null || env.isBlank() ? "all" : env;
        var listed = agentRegistryService.listAll().stream()
                .filter(agent -> agent.status() != AgentStatus.RETIRED)
                .filter(agent -> "all".equals(scope) || scope.equals(agent.env()))
                .toList();
        var agents = usage.apply(listed, scope);
        var snap = usage.snapshotOf(scope);
        var online = (int) agents.stream()
                .filter(agent -> agent.status() == AgentStatus.ONLINE || agent.status() == AgentStatus.DEGRADED)
                .count();
        var biz = (int) agents.stream().filter(agent -> "biz".equals(agent.category())).count();
        var dev = (int) agents.stream().filter(agent -> "dev".equals(agent.category())).count();
        Long calls = snap.callsKnown() ? snap.callTotal() : null;
        Double cost = snap.costKnown() ? snap.costTotal() : null;
        var scored = agents.stream().map(AgentSummary::score).filter(score -> score != null).mapToDouble(Double::doubleValue).toArray();
        Double avg = scored.length == 0 ? quality.average() : Math.round(java.util.Arrays.stream(scored).average().orElseThrow() * 100.0) / 100.0;
        var kpi = new Kpi(online, agents.size(), biz, dev, calls, snap.trendPct(), cost, avg, GATE, pendingApprovals.getAsInt());
        var costByAgent = new ArrayList<Map<String, Object>>();
        if (snap.costKnown()) {
            snap.cost().forEach((agent, amount) -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("agent", agent);
                row.put("costCny", amount);
                var budget = snap.budgets().get(agent);
                if (budget != null) {
                    row.put("dailyBudgetCny", budget);
                }
                costByAgent.add(row);
            });
        }
        return new Overview(kpi, agents, alerts(scope, agents, snap), costByAgent);
    }

    private List<Alert> alerts(String env, List<AgentSummary> agents, AgentUsageService.Snapshot snap) {
        var rows = new ArrayList<Alert>();
        for (var finding : findings.openFindings()) {
            if (!"all".equals(env) && !env.equals(finding.env())) {
                continue;
            }
            rows.add(new Alert(finding.firstSeen().toString(), levelOf(finding.kind()), finding.agent(), finding.kind(),
                    textOf(finding.agent(), finding.kind()), null));
        }
        var now = Instant.now().toString();
        for (var agent : agents) {
            if (agent.score() != null && agent.score() < GATE) {
                rows.add(new Alert(now, "ERROR", agent.name(), "GATE_FAILED",
                        agent.displayName() + " 评测 " + String.format(Locale.ROOT, "%.2f", agent.score())
                                + " 低于门禁 " + String.format(Locale.ROOT, "%.2f", GATE), null));
            }
            if (snap.costKnown() && agent.costCny() != null && agent.dailyBudgetCny() != null && agent.dailyBudgetCny() > 0
                    && agent.costCny() / agent.dailyBudgetCny() >= 0.8) {
                var pct = (int) Math.round(agent.costCny() / agent.dailyBudgetCny() * 100);
                rows.add(new Alert(now, "WARN", agent.name(), "BUDGET_WARN",
                        agent.name() + " 今日已用 ¥" + String.format(Locale.ROOT, "%.2f", agent.costCny())
                                + "，达到日预算的 " + pct + "%", null));
            }
        }
        rows.sort(Comparator.comparing(Alert::at).reversed());
        return rows;
    }

    private static String levelOf(String kind) {
        return switch (kind) {
            case "ZOMBIE", "BUDGET_WARN", "PROMPT_DRIFT" -> "WARN";
            default -> "ERROR";
        };
    }

    private static String textOf(String agent, String kind) {
        return switch (kind) {
            case "OFFLINE" -> agent + " 无就绪实例或心跳超时";
            case "UNREGISTERED" -> "发现未登记的运行实例 " + agent + "，它没有虚拟 Key，模型调用会被拒绝";
            case "ZOMBIE" -> agent + " 已就绪，但 7 天没有调用";
            case "RETIRE_INCOMPLETE" -> agent + " 已下线但仍在运行";
            case "VERSION_MISMATCH" -> agent + " 运行版本与登记版本不一致";
            default -> agent + " " + kind;
        };
    }

    private static com.keel.server.integration.litellm.LiteLlmClient costsGateway(CostService costs) {
        return new com.keel.server.integration.litellm.LiteLlmClient("", "") {
            @Override
            public java.util.List<VirtualKey> keys() {
                return costs.cost().keys().stream()
                        .map(key -> new VirtualKey(key.alias(), List.of(), key.dailyBudgetCny(), key.spentCny(), key.blocked()))
                        .toList();
            }
        };
    }
}
