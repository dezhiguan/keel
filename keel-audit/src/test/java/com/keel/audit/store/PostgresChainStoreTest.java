package com.keel.audit.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.audit.chain.ChainStore;
import com.keel.audit.chain.ChainVerifyJob;
import com.keel.audit.chain.HashChainService;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PostgresChainStoreTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("keel_audit");

    private static HikariDataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void migrate() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void appendsFormOneChainAndAnOlderMonthLandsInItsPartition() throws Exception {
        var store = new PostgresChainStore(dataSource);
        var chain = new HashChainService(store);
        chain.append(json.readTree("{\"event_id\":\"pg1\",\"agent\":\"askdb\",\"ts\":\"2026-10-04T03:00:00Z\"}"), true);
        chain.append(json.readTree("{\"event_id\":\"pg2\",\"agent\":\"askdb\",\"ts\":\"2026-10-04T03:00:01Z\"}"), false);
        chain.append(json.readTree("{\"event_id\":\"old\",\"agent\":\"askdb\",\"ts\":\"2026-09-15T00:00:00Z\"}"), false);

        var rows = store.list("askdb");
        assertThat(rows).extracting(ChainStore.Link::eventId).containsExactly("pg1", "pg2", "old");
        assertThat(rows.get(1).prevHash()).isEqualTo(rows.get(0).hash());
        assertThat(rows.get(2).prevHash()).isEqualTo(rows.get(1).hash());
        try (Connection connection = dataSource.getConnection()) {
            var head = new AuditEventMapper().readHead(connection, "askdb");
            assertThat(head.count()).isEqualTo(3);
            try (var statement = connection.createStatement();
                 var found = statement.executeQuery("SELECT to_regclass('audit_event_2026_09')")) {
                assertThat(found.next()).isTrue();
                assertThat(found.getString(1)).contains("audit_event_2026_09");
            }
        }
        assertThat(new ChainVerifyJob(chain, store).verify("askdb")).isEmpty();
        var tampered = List.of(rows.get(0),
                new ChainStore.Link("pg2", "askdb", rows.get(1).prevHash(), rows.get(1).hash(), "{\"tampered\":true}"));
        assertThat(chain.brokenAt(tampered)).contains("pg2");
    }

    @Test
    void duplicateInsertDoesNotAdvanceTheHead() throws Exception {
        var store = new PostgresChainStore(dataSource);
        var chain = new HashChainService(store);
        var event = json.readTree("{\"event_id\":\"dup\",\"agent\":\"careermate\",\"ts\":\"2026-10-04T03:00:00Z\",\"risk\":\"high\"}");
        chain.append(event, true);
        assertThatThrownBy(() -> chain.append(event, true))
                .isInstanceOf(HashChainService.AuditAppendException.class);
        assertThat(store.list("careermate")).extracting(ChainStore.Link::eventId).containsExactly("dup");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(new AuditEventMapper().readHead(connection, "careermate").count()).isEqualTo(1);
        }
    }

    @Test
    void concurrentAppendsStayOnOneChain() throws Exception {
        var store = new PostgresChainStore(dataSource);
        var chain = new HashChainService(store);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                int id = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(2, TimeUnit.SECONDS);
                        chain.append(json.readTree("{\"event_id\":\"c" + id + "\",\"agent\":\"ops\",\"ts\":\"2026-10-04T03:00:0" + id + "Z\"}"), false);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }
        var rows = store.list("ops");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).prevHash()).isEmpty();
        assertThat(rows.get(1).prevHash()).isEqualTo(rows.get(0).hash());
    }

}

class PostgresChainOrderTest {
    @Test
    void orderWalksFromTheGenesisLink() {
        var second = new ChainStore.Link("b", "a", "h1", "h2", "{}");
        var first = new ChainStore.Link("a", "a", "", "h1", "{}");
        assertThat(PostgresChainStore.order(List.of(second, first))).containsExactly(first, second);
    }
}
