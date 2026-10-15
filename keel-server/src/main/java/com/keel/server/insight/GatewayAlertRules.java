package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;

import java.util.ArrayList;
import java.util.List;

/** Turns gateway alert rules into overview alerts. Budget and auth failures are not routing changes. */
public final class GatewayAlertRules {
    private GatewayAlertRules() {}

    public record Hit(String kind, String agent, String text) {}

    public static List<Hit> evaluate(List<LiteLlmClient.AlertRule> rules, List<LiteLlmClient.Spend> bills,
                                    List<CostService.Key> keys) {
        var hits = new ArrayList<Hit>();
        for (var rule : rules) {
            if ("usage_missing".equals(rule.metric())) {
                var pending = bills.stream().filter(row -> "PENDING".equals(row.status())).count();
                if (pending >= rule.threshold()) {
                    hits.add(new Hit("LLM_USAGE_MISSING", "", rule.name() + "：待计费 " + pending + " 条"));
                }
            } else if ("channel_failure".equals(rule.metric())) {
                var failed = bills.stream().filter(LiteLlmClient.Spend::timedOut).count();
                if (failed >= rule.threshold()) {
                    hits.add(new Hit("CHANNEL_FAULT", "", rule.name() + "：渠道超时 " + failed + " 次"));
                }
            } else if ("budget".equals(rule.metric())) {
                for (var key : keys) {
                    if (key.dailyBudgetCny() <= 0) continue;
                    var used = (key.spentCny() + key.reservedCny()) / key.dailyBudgetCny();
                    if (used >= rule.threshold()) {
                        hits.add(new Hit("BUDGET_WARN", key.agent(), rule.name() + "：" + key.alias() + " 预算占用超过阈值"));
                    }
                }
            }
        }
        return hits;
    }
}
