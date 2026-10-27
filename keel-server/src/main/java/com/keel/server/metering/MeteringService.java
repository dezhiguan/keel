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
        var ledger = costs.ledger();
        for (var env : List.of("all", "dev", "test", "staging", "prod")) {
            reconcile(env, costs.summarize(ledger, env, "all"), now);
        }
    }

    /** Rebuild every available day, including sealed days corrected by late settlement. */
    public UsageMath.Slice reconcile(String env, CostService.Result history, Instant now) {
        var today = LocalDate.ofInstant(now, SHANGHAI);
        var days = new java.util.TreeMap<LocalDate, java.util.List<com.keel.server.integration.litellm.LiteLlmClient.Spend>>();
        for (var bill : history.bills()) {
            var at = Instant.parse(bill.ts());
            if (at.isAfter(now)) continue;
            var day = LocalDate.ofInstant(at, SHANGHAI);
            days.computeIfAbsent(day, ignored -> new java.util.ArrayList<>()).add(bill);
        }
        days.putIfAbsent(today, List.of());
        days.forEach((day, bills) -> store.replaceOpen(day, env, slice(bills)));
        store.closeBefore(today);
        // Return the ledger total directly: previously missing/incorrect daily rows cannot reduce it.
        var total = slice(days.values().stream().flatMap(List::stream).toList());
        // A recovered day amount has no per-call/token details. Preserve already observed historical usage.
        var knownUsage = UsageMath.cumulative(store.sumClosed(env), store.open(env, today), store.distinctUsers(env));
        return new UsageMath.Slice(knownUsage.calls(), knownUsage.tokens(), total.costCny(), knownUsage.users());
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
