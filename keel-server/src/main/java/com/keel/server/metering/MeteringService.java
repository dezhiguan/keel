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
        for (var env : List.of("all", "dev", "test", "staging", "prod")) {
            rollup(env, costs.cost(env, "today"), now);
        }
    }

    public UsageMath.Slice rollup(String env, CostService.Result todayBills, Instant now) {
        var day = LocalDate.ofInstant(now, SHANGHAI);
        store.closeBefore(day);
        if (todayBills.models().isEmpty() && todayBills.keys().isEmpty() && todayBills.bills().isEmpty()) {
            var open = store.open(env, day);
            var closed = store.sumClosed(env);
            return UsageMath.cumulative(closed, open == null ? UsageMath.Slice.unknown() : open, store.distinctUsers(env));
        }
        long tokens = 0;
        var cost = BigDecimal.ZERO;
        var requests = new java.util.HashSet<String>();
        for (var bill : todayBills.bills()) {
            if (!"SETTLED".equals(bill.status()) || bill.costCny() == null) continue;
            cost = cost.add(BigDecimal.valueOf(bill.costCny()));
            int input = bill.inputTokens() == null ? 0 : bill.inputTokens();
            int output = bill.outputTokens() == null ? 0 : bill.outputTokens();
            tokens += (long) input + output;
            if (bill.requestId() != null && !bill.requestId().isBlank()) requests.add(bill.requestId());
        }
        var today = new UsageMath.Slice((long) requests.size(), tokens, cost, null);
        store.replaceOpen(day, env, today);
        return UsageMath.cumulative(store.sumClosed(env), today, store.distinctUsers(env));
    }

    public UsageMath.Slice today(String env, Instant now) {
        var open = store.open(env, LocalDate.ofInstant(now, SHANGHAI));
        return open == null ? UsageMath.Slice.unknown() : open;
    }

    public LocalDate earliest(String env) {
        return store.earliest(env);
    }
}
