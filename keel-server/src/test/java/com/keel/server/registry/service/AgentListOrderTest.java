package com.keel.server.registry.service;

import com.keel.server.PostgresTestConfig;
import com.keel.server.registry.model.dto.AgentSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class AgentListOrderTest {
    @Autowired AgentRegistryService registry;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void pageIsNewestFirstAndBreaksTiesByName() {
        insert("sort-order-older", "AGENT", false, "2019-01-01T00:00:00Z");
        insert("sort-order-same-b", "AGENT", false, "2020-01-02T00:00:00Z");
        insert("sort-order-same-a", "AGENT", false, "2020-01-02T00:00:00Z");
        insert("sort-order-newer", "AGENT", false, "2024-06-01T00:00:00Z");
        insert("sort-order-service", "SERVICE", false, "2025-01-01T00:00:00Z");
        insert("sort-order-removed", "AGENT", true, "2026-01-01T00:00:00Z");

        var page = registry.page("all", "all", null, "sort-order-", 1, 20);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items()).extracting(AgentSummary::name)
                .containsExactly("sort-order-newer", "sort-order-same-a", "sort-order-same-b", "sort-order-older");
    }

    private void insert(String name, String kind, boolean removed, String createdAt) {
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness, removed, created_at)
                VALUES (?, ?, ?, 'code', 'python', 'QA', 'qa', 'REGISTERED', 'k8s', ?, ?::timestamptz)
                """, name, name, kind, removed, createdAt);
    }
}
