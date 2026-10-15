package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAlertRulesTest {
    @Test void budgetAndChannelRulesBecomeOverviewAlerts() {
        var rules = List.of(
                new LiteLlmClient.AlertRule("1", "预算", "budget", 0.8, "1d", "alias"),
                new LiteLlmClient.AlertRule("2", "渠道", "channel_failure", 1, "1h", "model"));
        var bills = List.of(new LiteLlmClient.Spend("echo-dev", "qwen-plus", 1.0, "2026-10-15T00:00:00Z", 10L, true,
                "req", 1, 1, 0, "FAILED", "a", "", "v", "primary", null));
        var keys = List.of(new CostService.Key("echo-dev", "echo", "dev", List.of("qwen-plus"), 10, 9, 0, false, 1, null, null, null));
        var hits = GatewayAlertRules.evaluate(rules, bills, keys);
        assertThat(hits).extracting(GatewayAlertRules.Hit::kind).containsExactly("BUDGET_WARN", "CHANNEL_FAULT");
    }
}
