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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("37");

        List<String> tables = jdbc.queryForList("""
            SELECT tablename FROM pg_tables
            WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
            ORDER BY tablename
            """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
            "agent", "agent_version", "agent_instance", "agent_resource",
            "agent_employee_read_scope", "assistant_analysis_scope", "assistant_analysis_task", "assistant_analysis_attempt", "assistant_analysis_command", "reconcile_finding", "tool", "tool_version", "agent_tool_grant",
            "approval_policy", "approval_request", "agent_run", "release_record",
            "route_snapshot", "console_audit_event", "invoke_trace", "prompt_promotion",
            "prompt_code_version", "prompt_label_snapshot", "agent_self_check", "eval_run", "eval_turn",
            "service_assertion_jti", "devflow_job", "devflow_batch", "devflow_stage_run",
            "devflow_execution", "devflow_artifact", "devflow_holdout", "devflow_holdout_result", "devflow_setting", "devflow_sandbox_run",
            "devflow_tool_grant",
            "usage_daily", "skill", "skill_version", "agent_skill_grant",
            "user_memory", "channel", "channel_credential", "channel_usage", "channel_feedback",
            "agent_session", "session_message", "session_summary", "session_fact_state", "debug_chat_request", "insight_snapshot", "insight_observation", "insight_trace", "insight_trace_bill");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='debug_chat_request'::regclass AND contype='f'", Integer.class)).isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='session_fact_state'::regclass AND contype='f'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='session_summary'::regclass AND contype='f'", Integer.class)).isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_employee_read_scope",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='agent_employee_read_scope'::regclass AND contype='f'",Integer.class)).isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_analysis_scope", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tool_grant WHERE tool_name IN ('keel.tasks.command','keel.tasks.execution')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid IN ('assistant_analysis_scope'::regclass,'assistant_analysis_task'::regclass,'assistant_analysis_attempt'::regclass) AND contype='f'", Integer.class)).isZero();

        List<String> runConstraints = jdbc.queryForList("""
            SELECT conname FROM pg_constraint WHERE conrelid = 'agent_run'::regclass
            """, String.class);
        assertThat(runConstraints).contains("agent_run_pkey", "uk_agent_run_idempotency",
            "ck_agent_run_status", "ck_agent_run_reason");

        List<String> requestConstraints = jdbc.queryForList("""
            SELECT conname FROM pg_constraint WHERE conrelid = 'approval_request'::regclass
            """, String.class);
        assertThat(requestConstraints).contains("ck_approval_request_subject",
            "ck_approval_request_status", "ck_approval_request_risk", "ck_approval_request_env",
            "approval_request_run_id_fkey");

        List<String> indexes = jdbc.queryForList("""
            SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()
            """, String.class);
        assertThat(indexes).contains("ix_agent_run_status_deadline", "ix_approval_request_pending",
            "ix_reconcile_finding_open", "route_snapshot_pkey",
            "ix_console_audit_event_ts", "ix_invoke_trace_started", "ix_prompt_promotion_latest", "ix_devflow_execution_queue",
            "debug_chat_request_pkey", "debug_chat_request_expiry");

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
