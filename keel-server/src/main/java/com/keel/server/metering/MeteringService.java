package com.keel.server.metering;

import com.keel.server.insight.CostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class MeteringService {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final UsageDailyStore store;
    private final CostService costs;

    @Autowired
    public MeteringService(JdbcTemplate jdbc, CostService costs) {
        this(new JdbcUsageDailyStore(jdbc), costs);
    }

    MeteringService(UsageDailyStore store, CostService costs) {
        this.store = store;
        this.costs = costs;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Shanghai")
    public void hourly() {
        var now = Instant.now();
        var today = LocalDate.ofInstant(now, SHANGHAI);
        var yesterday = today.minusDays(1);
        for (var env : List.of("all", "dev", "test", "staging", "prod")) {
            // Finalize the previous calendar day before sealing it. The 23:05
            // snapshot cannot include calls made during the last 55 minutes.
            rollupDay(env, costs.cost(env, "all"), yesterday);
        }
        store.closeBefore(today);
        for (var env : List.of("all", "dev", "test", "staging", "prod")) {
            rollup(env, costs.cost(env, "today"), now);
        }
    }

    void rollupDay(String env, CostService.Result history, LocalDate day) {
        var bills = history.bills().stream().filter(bill -> {
            try {
                return LocalDate.ofInstant(Instant.parse(bill.ts()), SHANGHAI).equals(day);
            } catch (RuntimeException ignored) {
                return false;
            }
        }).toList();
        if (bills.isEmpty()) return;
        store.replaceOpen(day, env, slice(bills));
    }

    public UsageMath.Slice rollup(String env, CostService.Result todayBills, Instant now) {
        var day = LocalDate.ofInstant(now, SHANGHAI);
        if (todayBills.models().isEmpty() && todayBills.keys().isEmpty() && todayBills.bills().isEmpty()) {
            var open = store.open(env, day);
            var closed = store.sumClosed(env);
            return UsageMath.cumulative(closed, open == null ? UsageMath.Slice.unknown() : open, store.distinctUsers(env));
        }
        var today = slice(todayBills.bills());
        store.replaceOpen(day, env, today);
        return UsageMath.cumulative(store.sumClosed(env), today, store.distinctUsers(env));
    }

    private static UsageMath.Slice slice(List<com.keel.server.integration.litellm.LiteLlmClient.Spend> bills) {
        long tokens = 0;
        var cost = BigDecimal.ZERO;
        var requests = new java.util.HashSet<String>();
        for (var bill : bills) {
            if (!"SETTLED".equals(bill.status()) || bill.costCny() == null) continue;
            cost = cost.add(BigDecimal.valueOf(bill.costCny()));
            int input = bill.inputTokens() == null ? 0 : bill.inputTokens();
            int output = bill.outputTokens() == null ? 0 : bill.outputTokens();
            tokens += (long) input + output;
            if (bill.requestId() != null && !bill.requestId().isBlank()) requests.add(bill.requestId());
        }
        return new UsageMath.Slice((long) requests.size(), tokens, cost, null);
    }

    public UsageMath.Slice today(String env, Instant now) {
        var open = store.open(env, LocalDate.ofInstant(now, SHANGHAI));
        return open == null ? UsageMath.Slice.unknown() : open;
    }

    public LocalDate earliest(String env) {
        return store.earliest(env);
    }
}
