package com.keel.server.metering;

import com.keel.server.insight.CostService;
import com.keel.server.integration.litellm.LiteLlmClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.HashMap;
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
        service.reconcile("prod", history, Instant.parse("2026-10-16T00:00:00Z"));
        var yesterday = store.days.get(LocalDate.of(2026, 10, 15));
        assertThat(yesterday.calls()).isEqualTo(1);
        assertThat(yesterday.costCny()).isEqualByComparingTo("0.25");
    }

    @Test void rebuildsMissingOlderDaysAndCorrectsSealedDaysWithoutDoubleCounting() {
        var store = new Store();
        var service = new MeteringService(store, null);
        var now = Instant.parse("2026-10-27T06:00:00Z");
        var older = spend("2026-10-24T12:00:00Z", "old", 3.0);
        var yesterday = spend("2026-10-26T15:50:00Z", "yesterday", 80.069619);
        var today = spend("2026-10-26T16:00:00Z", "today", 0.03);
        var first = history(List.of(older, yesterday, today));
        var total = service.reconcile("prod", first, now);
        assertThat(total.costCny()).isEqualByComparingTo("83.099619");
        assertThat(total.calls()).isEqualTo(3);
        assertThat(store.days.get(LocalDate.of(2026, 10, 24)).costCny()).isEqualByComparingTo("3");
        assertThat(service.today("prod", now).costCny()).isEqualByComparingTo("0.03");
        assertThat(service.reconcile("prod", first, now).costCny()).isEqualByComparingTo(total.costCny());
        var late = spend("2026-10-24T12:01:00Z", "late-settlement", 2.0);
        var corrected = service.reconcile("prod", history(List.of(older, yesterday, today, late)), now);
        assertThat(corrected.costCny()).isEqualByComparingTo("85.099619");
        assertThat(store.days.get(LocalDate.of(2026, 10, 24)).costCny()).isEqualByComparingTo("5");
        assertThat(service.reconcile("prod", first, now.plusSeconds(86400)).costCny()).isEqualByComparingTo("83.099619");
        assertThat(service.today("prod", now.plusSeconds(86400)).costCny()).isZero();
    }

    private static CostService.Result history(List<LiteLlmClient.Spend> bills) {
        return new CostService.Result(0, 0, Map.of(), Map.of(), List.of(), List.of(), List.of(), bills, 0);
    }

    private static LiteLlmClient.Spend spend(String at, String id, double cost) {
        return new LiteLlmClient.Spend("askdb-prod", "qwen-plus", cost, at, 10L, false,
                id, 2, 3, 0, "SETTLED", id, "", "v1", "primary", null);
    }

    private static final class Store implements UsageDailyStore {
        UsageMath.Slice slice;
        final Map<LocalDate, UsageMath.Slice> days = new HashMap<>();
        public void replaceOpen(LocalDate day, String env, UsageMath.Slice value) { slice = value; days.put(day, value); }
        LocalDate closedBefore;
        public void closeBefore(LocalDate day) { closedBefore = day; }
        public UsageMath.Slice sumClosed(String env) {
            return UsageMath.Slice.closed(days.entrySet().stream().filter(e -> e.getKey().isBefore(closedBefore)).map(Map.Entry::getValue).toList());
        }
        public UsageMath.Slice open(String env, LocalDate day) { return days.get(day); }
        public Long distinctUsers(String env) { return null; }
        public LocalDate earliest(String env) { return null; }
    }
}
