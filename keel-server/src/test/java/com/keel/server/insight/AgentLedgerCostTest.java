package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.entity.Agent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLedgerCostTest {
    private static AgentSummary agent() {
        var a = new Agent(); a.setName("keel-assistant");
        return AgentSummary.of(a).withUsage(1L, 3L, null, 999.0, 110.0, null);
    }
    private static LiteLlmClient.Spend spend(String alias, String at, Double cost, String status) {
        return new LiteLlmClient.Spend(alias, "qwen", cost, at, 10L, false,
                at + alias, 2, 3, 0, status, at + alias, "", "v1", "primary", null);
    }
    @Test void naturalDayAndLifetimeUseBillsInsteadOfBudgetCounter() {
        var bills = List.of(
                spend("keel-assistant-staging", "2026-10-26T15:59:59Z", 80.069619, "SETTLED"),
                spend("keel-assistant-staging", "2026-10-26T16:00:00Z", 0.03, "SETTLED"),
                spend("keel-assistant-staging", "2026-10-26T16:00:01Z", null, "PENDING"),
                spend("keel-assistant-staging", "2026-10-24T12:00:00Z", null, "PENDING"),
                spend("keel-assistant-staging", "2026-10-26T17:00:00Z", 77.0, "FAILED"),
                spend("keel-assistant-prod", "2026-10-26T18:00:00Z", 2.0, "SETTLED"));
        var ledger = new CostService.Ledger(bills, List.of(), List.of(), List.of());
        var now = Instant.parse("2026-10-27T06:00:00Z");
        var staging = CostService.applyAgentCosts(List.of(agent()), ledger, "staging", now).getFirst();
        assertThat(staging.costCny()).isEqualTo(0.03);
        assertThat(staging.totalCostCny()).isEqualTo(80.099619);
        assertThat(staging.pendingBillCount()).isEqualTo(1);
        assertThat(staging.totalPendingBillCount()).isEqualTo(2);
        assertThat(staging.dailyBudgetCny()).isEqualTo(110.0);
        var all = CostService.applyAgentCosts(List.of(agent()), ledger, "all", now).getFirst();
        assertThat(all.costCny()).isEqualTo(2.03);
        assertThat(all.totalCostCny()).isEqualTo(82.099619);
        var tomorrow = CostService.applyAgentCosts(List.of(agent()), ledger, "all", now.plusSeconds(86400)).getFirst();
        assertThat(tomorrow.costCny()).isZero();
        assertThat(tomorrow.totalCostCny()).isEqualTo(all.totalCostCny());
        assertThat(tomorrow.pendingBillCount()).isZero();
        assertThat(tomorrow.totalPendingBillCount()).isEqualTo(2);
    }
}
