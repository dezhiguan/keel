package com.keel.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcSpendLedgerTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach void clearLedger() throws Exception {
        new JdbcSpendLedger(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("TRUNCATE llm_spend,llm_daily_recovery");
        }
    }

    @Test
    @EnabledIf("dockerIsAvailable")
    void cursorReadsEveryAttemptFromPostgres() {
        var ledger = new JdbcSpendLedger(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ledger.append(row("a", "2026-10-15T00:00:00Z"));
        ledger.append(row("b", "2026-10-15T00:01:00Z"));
        var first = ledger.page("echo-dev", null, 1);
        assertThat(first.rows()).extracting(Gateway.Spend::attemptId).containsExactly("b");
        ledger.append(row("c", "2026-10-15T00:02:00Z"));
        var second = ledger.page("echo-dev", first.nextCursor(), 10);
        assertThat(second.rows()).extracting(Gateway.Spend::attemptId).containsExactly("a");
        assertThat(ledger.latestSettledByModel()).extracting(Gateway.Spend::attemptId).containsExactly("c");
    }

    @Test
    @EnabledIf("dockerIsAvailable")
    void recoveryAddsOnlyTheMissingDailyMoneyAndNeverInventsACall() throws Exception {
        var ledger = new JdbcSpendLedger(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ledger.importRecent(java.util.List.of(row("recovery-a", "2026-10-16T01:00:00Z")));
        ledger.importRecent(java.util.List.of(row("recovery-a", "2026-10-16T01:00:00Z")));
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO llm_daily_recovery(day,alias,settled_cny,source,evidence_sha256) VALUES ('2026-10-16','echo-dev',80.01,'redis-settled','verified-backup')");
        }
        var recovered = ledger.page("echo-dev", null, 200).rows();
        assertThat(recovered.stream().filter(r -> r.ts().isAfter(Instant.parse("2026-10-15T16:00:00Z").minusMillis(1))).map(Gateway.Spend::costCny).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("80.01");
        assertThat(recovered).filteredOn(r -> "daily-recovery".equals(r.routeVersion())).singleElement().satisfies(r -> {
            assertThat(r.requestId()).isEmpty(); assertThat(r.model()).isEmpty();
            assertThat(r.inputTokens()).isNegative(); assertThat(r.costCny()).isEqualByComparingTo("80.00");
        });
        ledger.append(row("recovery-b", "2026-10-16T02:00:00Z"));
        var again = ledger.page("echo-dev", null, 200).rows();
        assertThat(again).filteredOn(r -> "daily-recovery".equals(r.routeVersion())).singleElement().satisfies(r -> assertThat(r.costCny()).isEqualByComparingTo("79.99"));
    }

    static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable error) {
            return false;
        }
    }

    private static Gateway.Spend row(String attemptId, String instant) {
        return new Gateway.Spend("echo-dev", "qwen-plus", 2, 1, new BigDecimal("0.010000"), "request",
                Instant.parse(instant), 3, false, "SETTLED", BigDecimal.ZERO, attemptId, "trace", "2026-10-08", "primary", -1);
    }
}
