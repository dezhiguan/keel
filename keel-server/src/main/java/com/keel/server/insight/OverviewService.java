package com.keel.server.insight;

import com.keel.server.discovery.FindingBook;
import com.keel.server.metering.MeteringService;
import com.keel.server.metering.UsageMath;
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
import java.util.function.Function;

@Service
public class OverviewService {
    public static final double GATE = 0.85;

    public record Overview(Kpi kpi, List<AgentSummary> agents, List<Alert> alerts, List<Map<String, Object>> costByAgent,
                           Cumulative cumulative, List<Metric> metrics, LayerValue.Layers layers) {}

    public record Cumulative(Integer agents, Long calls, Long tokens, Double costCny, Long users, String since) {}

    public record Metric(String key, String definition, String source, boolean connected) {}

    public record Kpi(Integer onlineAgents, Integer totalAgents, Integer bizCount, Integer devCount,
                      Long calls, Double callsTrendPct, Double modelCostCny, Double avgScore,
                      Double gateThreshold, Integer pendingApprovals, Integer pendingModelBills) {}

    public record Alert(String at, String level, String agent, String kind, String text, String link) {}

    private final AgentRegistryService agentRegistryService;
    private final AgentUsageService usage;
    private final QualityService quality;
    private final Function<String, Integer> pendingApprovals;
    private final FindingBook findings;
    private final CostService costs;
    private final MeteringService metering;
    @Autowired(required = false)
    private LayerValueService layerValue;

    @Autowired
    public OverviewService(AgentRegistryService agentRegistryService, AgentUsageService usage, QualityService quality,
                           Function<String, Integer> pendingApprovals, FindingBook findings, CostService costs,
                           MeteringService metering) {
        this.agentRegistryService = agentRegistryService;
        this.usage = usage;
        this.quality = quality;
        this.pendingApprovals = pendingApprovals;
        this.findings = findings;
        this.costs = costs;
        this.metering = metering;
    }

    /** Spend-ledger tests construct the service without a usage cache or finding book. */
    public OverviewService(AgentRegistryService agentRegistryService, CostService costs, QualityService quality,
                           Function<String, Integer> pendingApprovals) {
        this(agentRegistryService,
                new AgentUsageService(new com.keel.server.integration.langfuse.LangfuseClient("", "", ""),
                        costsGateway(costs), quality,
                        new EvalQueryService(new com.keel.server.integration.langfuse.LangfuseClient("", "", ""), name -> null),
                        com.keel.server.insight.SavedTraces.EMPTY),
                quality, pendingApprovals, new FindingBook(null), costs, null);
    }

    public Overview overview(String env, String range) {
        var scope = env == null || env.isBlank() ? "all" : env;
        var listed = agentRegistryService.listAll().stream()
                .filter(agent -> agent.status() != AgentStatus.RETIRED)
                .filter(agent -> "all".equals(scope) || scope.equals(agent.env()))
                .toList();
        var agents = usage.apply(listed, scope);
        var window = "today".equals(range) || "all".equals(range) ? "24h" : range;
        var snap = usage.snapshotOf(scope, window);
        var daily = "24h".equals(range) ? snap : usage.snapshotOf(scope);
        var bills = costs.cost(scope, range);
        UsageMath.Slice ledger = null;
        UsageMath.Slice todayLedger = null;
        if (metering != null) {
            ledger = metering.rollup(scope, costs.cost(scope, "today"), Instant.now());
            todayLedger = metering.today(scope, Instant.now());
        }
        var online = (int) agents.stream().filter(agent -> agent.status() == AgentStatus.ONLINE).count();
        var biz = (int) agents.stream().filter(agent -> "biz".equals(agent.category())).count();
        var dev = (int) agents.stream().filter(agent -> "dev".equals(agent.category())).count();
        Long calls = snap.callsKnown() ? snap.callTotal() : null;
        Double cost = daily.costKnown() ? bills.totalCny() : null;
        if ("today".equals(range) && todayLedger != null) {
            calls = todayLedger.calls();
            cost = todayLedger.costCny() == null ? null : todayLedger.costCny().doubleValue();
        } else if ("all".equals(range) && ledger != null) {
            calls = ledger.calls();
            cost = ledger.costCny() == null ? null : ledger.costCny().doubleValue();
        }
        var scored = agents.stream().map(AgentSummary::score).filter(score -> score != null).toList();
        Double avg;
        if (!scored.isEmpty()) {
            avg = Math.round(scored.stream().mapToDouble(Double::doubleValue).average().orElseThrow() * 100.0) / 100.0;
        } else if (!snap.scores().isEmpty()) {
            avg = Math.round(snap.scores().values().stream().mapToDouble(Double::doubleValue).average().orElseThrow() * 100.0) / 100.0;
        } else {
            avg = quality.average();
        }
        var kpi = new Kpi(online, agents.size(), biz, dev, calls, snap.trendPct(), cost, avg, GATE,
                pendingApprovals.apply(scope), bills.pendingCount());
        var costByAgent = new ArrayList<Map<String, Object>>();
        if (daily.costKnown()) {
            bills.byAgent().forEach((agent, amount) -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("agent", agent);
                row.put("costCny", amount);
                var budget = "24h".equals(range) ? daily.budgets().get(agent) : null;
                if (budget != null) {
                    row.put("dailyBudgetCny", budget);
                }
                costByAgent.add(row);
            });
        }
        var registered = (int) agentRegistryService.listAll().stream()
                .filter(agent -> "all".equals(scope) || scope.equals(agent.env()))
                .count();
        var since = metering == null || metering.earliest(scope) == null ? null : metering.earliest(scope).toString();
        var cumulative = new Cumulative(registered,
                ledger == null ? null : ledger.calls(),
                ledger == null ? null : ledger.tokens(),
                ledger == null || ledger.costCny() == null ? null : ledger.costCny().doubleValue(),
                ledger == null ? null : ledger.users(),
                since);
        var metrics = List.of(
                new Metric("calls", "所选时间窗内已结算模型请求数，按 requestId 去重", calls == null ? "未接入" : "usage_daily", calls != null),
                new Metric("modelCost", "已结算的 costCny 之和", cost == null ? "未接入" : "keel_llm 账单", cost != null),
                new Metric("cumulativeCalls", "已封账各天之和 + 今天实时", cumulative.calls() == null ? "未接入" : "usage_daily", cumulative.calls() != null),
                new Metric("cumulativeTokens", "已结算账单的输入 token 加输出 token", cumulative.tokens() == null ? "未接入" : "keel_llm 账单", cumulative.tokens() != null),
                new Metric("cumulativeCost", "已封账花费 + 今天已结算花费", cumulative.costCny() == null ? "未接入" : "keel_llm 账单", cumulative.costCny() != null),
                new Metric("cumulativeUsers", "agent_run 中去重的 actor_user", cumulative.users() == null ? "未接入" : "agent_run", cumulative.users() != null),
                new Metric("agents", "注册中心中的智能体，含已下线，不含已删除", "registry", true));
        var layers = layerValue == null ? LayerValue.empty() : layerValue.compute(scope, names(agents, true), names(agents, false));
        return new Overview(kpi, agents, alerts(scope, agents, daily, bills), costByAgent, cumulative, metrics, layers);
    }

    private List<Alert> alerts(String env, List<AgentSummary> agents, AgentUsageService.Snapshot snap,
                               CostService.Result bills) {
        var rows = new ArrayList<Alert>();
        var missingByAgent = new LinkedHashMap<String, Integer>();
        var latestByAgent = new LinkedHashMap<String, String>();
        for (var bill : bills.bills()) {
            if (!"PENDING".equals(bill.status())) continue;
            var agent = CostService.agentOf(bill.alias());
            missingByAgent.merge(agent, 1, Integer::sum);
            latestByAgent.merge(agent, bill.ts(), (left, right) -> left.compareTo(right) >= 0 ? left : right);
        }
        missingByAgent.forEach((agent, count) -> rows.add(new Alert(latestByAgent.get(agent), "WARN", agent,
                "LLM_USAGE_MISSING", agent + " 有 " + count + " 条调用 usage 缺失，进入待计费", "/models")));
        for (var hit : GatewayAlertRules.evaluate(costs.alertRules(), bills.bills(), bills.keys())) {
            rows.add(new Alert(Instant.now().toString(), "WARN", hit.agent(), hit.kind(), hit.text(), "/models"));
        }
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

    private static List<String> names(List<AgentSummary> agents, boolean biz) {
        return agents.stream().filter(agent -> biz ? bizLayer(agent) : devLayer(agent)).map(AgentSummary::name)
                .filter(name -> name != null && !name.isBlank()).toList();
    }

    private static boolean bizLayer(AgentSummary agent) {
        return "BIZ".equals(agent.layer()) || (agent.layer() == null && "biz".equals(agent.category()));
    }

    private static boolean devLayer(AgentSummary agent) {
        return "DEV".equals(agent.layer()) || (agent.layer() == null && "dev".equals(agent.category()));
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
