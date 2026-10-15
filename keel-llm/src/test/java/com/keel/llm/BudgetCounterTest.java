package com.keel.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetCounterTest {
    @Test void reserveSettleAndReleaseAreIdempotent() {
        var budget = new BudgetCounter();
        assertThat(budget.reserve("echo-dev", "first", new BigDecimal("0.006"), new BigDecimal("0.01"))).isTrue();
        assertThat(budget.reserve("echo-dev", "first", new BigDecimal("0.006"), new BigDecimal("0.01"))).isTrue();
        assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0.006");
        assertThat(budget.reserve("echo-dev", "second", new BigDecimal("0.006"), new BigDecimal("0.01"))).isFalse();
        assertThat(budget.settle("echo-dev", "first", new BigDecimal("0.002"))).isTrue();
        assertThat(budget.settle("echo-dev", "first", new BigDecimal("0.002"))).isFalse();
        budget.release("echo-dev", "first");
        assertThat(budget.snapshot("echo-dev").settledCny()).isEqualByComparingTo("0.002");
        assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0");
        assertThat(budget.reserve("echo-dev", "second", new BigDecimal("0.006"), new BigDecimal("0.01"))).isTrue();
        budget.release("echo-dev", "second");
        budget.release("echo-dev", "second");
        assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0");
    }

    @Test void abandonedReservationIsReclaimedAndCannotSettleLate() {
        var instant = new AtomicReference<>(Instant.parse("2026-10-15T03:00:00Z"));
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant.get(), zone); }
            @Override public Instant instant() { return instant.get(); }
        };
        var budget = new BudgetCounter(clock);
        assertThat(budget.reserve("echo-dev", "2026-10-15@stale", new BigDecimal("0.01"), new BigDecimal("0.01"))).isTrue();
        instant.set(instant.get().plusSeconds(121));
        assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0.01");
        instant.set(instant.get().plusMillis(BudgetCounter.RESERVATION_MILLIS));
        assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> budget.settle("echo-dev", "2026-10-15@stale", BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(budget.reserve("echo-dev", "2026-10-15@fresh", new BigDecimal("0.01"), new BigDecimal("0.01"))).isTrue();
    }

    @Test void pendingReservationSurvivesOrphanCleanupAndIsIdempotent() {
        var instant = new AtomicReference<>(Instant.parse("2026-10-15T03:00:00Z"));
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant.get(), zone); }
            @Override public Instant instant() { return instant.get(); }
        };
        var budget = new BudgetCounter(clock);
        var id = "2026-10-15@pending";
        assertThat(budget.reserve("echo-dev", id, new BigDecimal("0.006"), new BigDecimal("0.01"))).isTrue();
        assertThat(budget.markPending("echo-dev", id)).isTrue();
        assertThat(budget.markPending("echo-dev", id)).isFalse();
        instant.set(instant.get().plusMillis(BudgetCounter.RESERVATION_MILLIS + 1));
        budget.release("echo-dev", id);
        assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0.006");
        assertThat(budget.reserve("echo-dev", "2026-10-15@other", new BigDecimal("0.006"),
                new BigDecimal("0.01"))).isFalse();
        assertThat(budget.settle("echo-dev", id, new BigDecimal("0.002"))).isTrue();
        assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0");
    }
}
