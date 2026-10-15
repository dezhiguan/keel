package com.keel.llm;

import org.junit.jupiter.api.Test;
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
