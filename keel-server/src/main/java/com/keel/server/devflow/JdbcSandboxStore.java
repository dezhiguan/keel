package com.keel.server.devflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcSandboxStore implements SandboxStore {
    private final JdbcTemplate jdbc;

    public JdbcSandboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextRunId() {
        var seq = jdbc.queryForObject("SELECT nextval('devflow_sandbox_seq')", Long.class);
        return "SB-" + String.format("%04d", seq == null ? 1 : seq);
    }

    @Override
    public void insert(SandboxRecord run) {
        jdbc.update("""
                INSERT INTO devflow_sandbox_run (
                    run_id, job_id, repo, git_ref, status, report, truncated, actor, created_at, deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.runId(), run.jobId(), run.repo(), run.ref(), run.status(), run.report(), run.truncated(),
                run.actor(), Timestamp.from(run.createdAt()), Timestamp.from(run.deadlineAt()));
    }

    @Override
    public SandboxRecord find(String runId) {
        var rows = jdbc.query(select() + " WHERE run_id = ?", this::map, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void update(SandboxRecord run) {
        jdbc.update("""
                UPDATE devflow_sandbox_run SET status = ?, report = ?, truncated = ? WHERE run_id = ?
                """, run.status(), run.report(), run.truncated(), run.runId());
    }

    @Override
    public List<SandboxRecord> active() {
        return jdbc.query(select() + " WHERE status IN ('QUEUED', 'RUNNING') ORDER BY created_at, run_id", this::map);
    }

    private static String select() {
        return """
                SELECT run_id, job_id, repo, git_ref, status, report, truncated, actor, created_at, deadline_at
                FROM devflow_sandbox_run
                """;
    }

    private SandboxRecord map(ResultSet rs, int row) throws SQLException {
        return new SandboxRecord(rs.getString("run_id"), rs.getString("job_id"), rs.getString("repo"),
                rs.getString("git_ref"), rs.getString("status"), rs.getString("report"), rs.getBoolean("truncated"),
                rs.getString("actor"), instant(rs, "created_at"), instant(rs, "deadline_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
