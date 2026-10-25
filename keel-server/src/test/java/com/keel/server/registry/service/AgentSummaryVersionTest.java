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
class AgentSummaryVersionTest {
    @Autowired AgentRegistryService registry;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void listPrefersFormalSnapshotWhenDraftWasUpdatedLater() {
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness)
                VALUES ('qa-summary-version', '版本摘要', 'AGENT', 'code', 'python', 'QA', 'qa', 'ONLINE', 'k8s')
                """);
        jdbc.update("""
                INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,gate_run_id,released_by,released_at)
                VALUES ('qa-summary-version','v3','staging','{"metadata":{"category":"biz"}}'::jsonb,'hash-v3','ev_v3','qa',
                        '2026-10-21T12:53:00+08'::timestamptz),
                       ('qa-summary-version','v0','staging','{"metadata":{"category":"biz"}}'::jsonb,'hash-draft',null,'qa',
                        '2026-10-25T11:08:00+08'::timestamptz)
                """);

        var summary = registry.listNewestFirst().stream()
                .filter(item -> "qa-summary-version".equals(item.name()))
                .findFirst()
                .orElseThrow();
        assertThat(summary).extracting(AgentSummary::version, AgentSummary::env)
                .containsExactly("v3", "staging");
        assertThat(registry.detail("qa-summary-version").summary().version()).isEqualTo("v3");
    }
}
