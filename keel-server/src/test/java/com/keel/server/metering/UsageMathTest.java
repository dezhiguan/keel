package com.keel.server.metering;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsageMathTest {
    @Test void cumulativeAddsClosedDaysToToday() {
        var closed = UsageMath.Slice.closed(List.of(
                new UsageMath.Slice(10L, 100L, new BigDecimal("1.50"), null)));
        var today = new UsageMath.Slice(3L, 30L, new BigDecimal("0.40"), null);
        var total = UsageMath.cumulative(closed, today, 4L);
        assertThat(total.calls()).isEqualTo(13);
        assertThat(total.tokens()).isEqualTo(130);
        assertThat(total.costCny()).isEqualByComparingTo("1.90");
        assertThat(total.users()).isEqualTo(4);
        assertThat(total.costCny()).isEqualByComparingTo(UsageMath.settledCost(List.of(
                new BigDecimal("1.50"), new BigDecimal("0.40"))));
    }

    @Test void missingTodayStaysUnknown() {
        var closed = UsageMath.Slice.closed(List.of(
                new UsageMath.Slice(10L, 100L, new BigDecimal("1.50"), null)));
        var total = UsageMath.cumulative(closed, UsageMath.Slice.unknown(), null);
        assertThat(total.calls()).isNull();
        assertThat(total.tokens()).isNull();
        assertThat(total.costCny()).isNull();
        assertThat(total.users()).isNull();
    }
}
