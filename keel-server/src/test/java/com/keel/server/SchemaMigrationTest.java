package com.keel.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    @Transactional
    void migratesAllPlatformTablesAndRunConstraints() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");

        List<String> tables = jdbc.queryForList("""
            SELECT tablename FROM pg_tables
            WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
            ORDER BY tablename
            """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
            "agent", "agent_version", "agent_instance", "agent_resource",
            "reconcile_finding", "tool", "tool_version", "agent_tool_grant",
            "approval_policy", "approval_request", "agent_run", "release_record",
            "route_snapshot");

        List<String> runConstraints = jdbc.queryForList("""
            SELECT conname FROM pg_constraint WHERE conrelid = 'agent_run'::regclass
            """, String.class);
        assertThat(runConstraints).contains("agent_run_pkey", "uk_agent_run_idempotency",
            "ck_agent_run_status", "ck_agent_run_reason");

        List<String> requestConstraints = jdbc.queryForList("""
            SELECT conname FROM pg_constraint WHERE conrelid = 'approval_request'::regclass
            """, String.class);
        assertThat(requestConstraints).contains("ck_approval_request_subject",
            "ck_approval_request_status", "approval_request_run_id_fkey");

        List<String> indexes = jdbc.queryForList("""
            SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()
            """, String.class);
        assertThat(indexes).contains("ix_agent_run_status_deadline", "ix_approval_request_pending",
            "ix_reconcile_finding_open", "route_snapshot_pkey");

        jdbc.update("""
            INSERT INTO agent (name, display_name, kind, runtime, owner_org, owner_user, status, liveness)
            VALUES ('schema-test', 'Schema Test', 'AGENT', 'code', 'test', 'test', 'DRAFT', 'OFFLINE')
            """);
        jdbc.update("""
            INSERT INTO agent_run (run_id, agent_name, env, trace_id, status, idempotency_key)
            VALUES ('schema-run', 'schema-test', 'dev', 'trace-schema', 'SUSPENDED', 'request-1')
            """);
        jdbc.update("""
            INSERT INTO approval_request (subject_type, subject_ref, agent_name, run_id, actor_user, summary)
            VALUES ('tool.call', 'test.tool', 'schema-test', 'schema-run', 'tester', 'Approve test')
            """);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM approval_request r JOIN agent_run a ON r.run_id = a.run_id
            WHERE a.agent_name = 'schema-test'
            """, Integer.class)).isEqualTo(1);
    }
}
