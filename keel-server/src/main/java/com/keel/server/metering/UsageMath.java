package com.keel.server.metering;

import java.math.BigDecimal;
import java.util.List;

/** Closed days plus today's realtime. A missing today does not become zero. */
public final class UsageMath {
    private UsageMath() {}

    public record Slice(Long calls, Long tokens, BigDecimal costCny, Long users) {
        public static Slice unknown() {
            return new Slice(null, null, null, null);
        }

        public static Slice closed(List<Slice> days) {
            long calls = 0;
            long tokens = 0;
            var cost = BigDecimal.ZERO;
            for (var day : days) {
                if (day.calls != null) calls += day.calls;
                if (day.tokens != null) tokens += day.tokens;
                if (day.costCny != null) cost = cost.add(day.costCny);
            }
            return new Slice(calls, tokens, cost, null);
        }
    }

    public static Slice cumulative(Slice closed, Slice today, Long distinctUsers) {
        return new Slice(plus(closed.calls, today.calls), plus(closed.tokens, today.tokens),
                plus(closed.costCny, today.costCny), distinctUsers);
    }

    public static BigDecimal settledCost(List<BigDecimal> costs) {
        var total = BigDecimal.ZERO;
        for (var cost : costs) {
            if (cost != null) total = total.add(cost);
        }
        return total;
    }

    private static Long plus(Long closed, Long today) {
        if (today == null) return null;
        return (closed == null ? 0 : closed) + today;
    }

    private static BigDecimal plus(BigDecimal closed, BigDecimal today) {
        if (today == null) return null;
        return (closed == null ? BigDecimal.ZERO : closed).add(today);
    }
}
