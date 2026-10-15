package com.keel.server.metering;

import com.keel.server.insight.CostService;
import com.keel.server.integration.litellm.LiteLlmClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MeteringServiceTest {
    @Test void previousDayIncludesCallsAfterTheLastHourlySnapshot() {
        var store = new Store();
        var service = new MeteringService(store, null);
        var history = new CostService.Result(0, 0, Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(
                spend("2026-10-15T15:50:00Z", "yesterday", 0.25),
                spend("2026-10-15T16:01:00Z", "today", 0.40)), 0);
        service.rollupDay("prod", history, LocalDate.of(2026, 10, 15));
        assertThat(store.slice.calls()).isEqualTo(1);
        assertThat(store.slice.costCny()).isEqualByComparingTo("0.25");
    }

    private static LiteLlmClient.Spend spend(String at, String id, double cost) {
        return new LiteLlmClient.Spend("askdb-prod", "qwen-plus", cost, at, 10L, false,
                id, 2, 3, 0, "SETTLED", id, "", "v1", "primary", null);
    }

    private static final class Store implements UsageDailyStore {
        UsageMath.Slice slice;
        public void replaceOpen(LocalDate day, String env, UsageMath.Slice value) { slice = value; }
        public void closeBefore(LocalDate day) {}
        public UsageMath.Slice sumClosed(String env) { return UsageMath.Slice.closed(List.of()); }
        public UsageMath.Slice open(String env, LocalDate day) { return slice; }
        public Long distinctUsers(String env) { return null; }
        public LocalDate earliest(String env) { return null; }
    }
}
