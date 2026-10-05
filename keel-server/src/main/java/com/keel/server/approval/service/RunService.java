package com.keel.server.approval.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.approval.model.OpenRun;
import com.keel.server.approval.model.SuspendedRunView;
import com.keel.server.common.KeelException;
import com.keel.server.common.PageResult;
import com.keel.server.common.TraceIds;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

@Service
public class RunService {
    private static final Duration DEFAULT_DEADLINE = Duration.ofHours(24);
    private static final Set<String> HUMAN = Set.of("input_required", "handoff");

    private final JdbcTemplate jdbc;
    private final AuditStore audits;
    private final java.time.Clock clock;
    private final AgentRegistryService registry;
    private final AgentEndpointClient agents;
    private final TransactionTemplate transactions;

    public RunService(JdbcTemplate jdbc, AuditStore audits, java.time.Clock clock, AgentRegistryService registry,
                      AgentEndpointClient agents, PlatformTransactionManager tx) {
        this.jdbc = jdbc;
        this.audits = audits;
        this.clock = clock;
        this.registry = registry;
        this.agents = agents;
        this.transactions = new TransactionTemplate(tx);
    }

    @Transactional
    public PageResult<SuspendedRunView> page(String agent, String env, int page, int size) {
        if (page < 1 || (size != 10 && size != 20 && size != 50 && size != 100)) {
            throw invalid();
        }
        expireDue();
        var agentFilter = agent == null ? "" : agent;
        var envFilter = ApprovalPolicyEngine.scope(env);
        var total = jdbc.queryForObject("""
                SELECT count(*) FROM agent_run
                WHERE status = 'SUSPENDED' AND suspend_reason IN ('input_required', 'handoff')
                  AND (? = '' OR agent_name = ?) AND (? = '' OR env = ?)
                """, Long.class, agentFilter, agentFilter, envFilter, envFilter);
        var items = jdbc.query(selectSql() + """
                 WHERE status = 'SUSPENDED' AND suspend_reason IN ('input_required', 'handoff')
                   AND (? = '' OR agent_name = ?) AND (? = '' OR env = ?)
                 ORDER BY created_at DESC, run_id DESC
                 LIMIT ? OFFSET ?
                """, this::map, agentFilter, agentFilter, envFilter, envFilter, size, (page - 1L) * size);
        return new PageResult<>(page, size, total == null ? 0 : total, items);
    }

    @Transactional
    public SuspendedRunView open(OpenRun request) {
        if (request == null || !ApprovalPolicyEngine.runId(request.runId()) || !HUMAN.contains(request.reason())) {
            throw invalid();
        }
        var agent = required(request.agent(), 64);
        var prompt = required(request.prompt(), 4000);
        var actor = required(request.actorUser(), 128);
        var env = ApprovalPolicyEngine.env(request.env());
        if (!agentExists(agent)) {
            throw notFound();
        }
        var existing = lock(request.runId());
        if (existing != null) {
            if (request.reason().equals(existing.reason()) && "SUSPENDED".equals(existing.status())) {
                return existing.view();
            }
            throw new KeelException(ErrorCode.RUN_NOT_RESUMABLE, ErrorCode.RUN_NOT_RESUMABLE.message());
        }
        var checkpoint = blankToNull(request.checkpointRef());
        if (checkpoint != null && checkpoint.length() > 512) {
            throw invalid();
        }
        var now = clock.instant();
        var deadline = deadline(request.deadline(), now);
        var traceId = firstNonBlank(request.traceId(), TraceIds.current());
        var token = "rt_" + UUID.randomUUID().toString().replace("-", "");
        audits.append(agent, env, "run.suspend", "high", "pending", request.runId(), traceId);
        jdbc.update("""
                INSERT INTO agent_run
                    (run_id, agent_name, env, trace_id, status, suspend_reason, suspend_ref, checkpoint_ref,
                     actor_user, deadline, prompt, resume_token)
                VALUES (?, ?, ?, ?, 'SUSPENDED', ?, ?, ?, ?, ?, ?, ?)
                """, request.runId(), agent, env, traceId, request.reason(), request.runId(),
                checkpoint, actor, Timestamp.from(deadline), prompt, token);
        return load(request.runId());
    }

    public void answer(String runId, String text) {
        if (!ApprovalPolicyEngine.runId(runId) || text == null || text.isBlank()) {
            throw invalid();
        }
        var expired = transactions.execute(status -> applyAnswer(runId, text.trim()));
        if (Boolean.TRUE.equals(expired)) {
            throw new KeelException(ErrorCode.RUN_EXPIRED, ErrorCode.RUN_EXPIRED.message());
        }
    }

    @Transactional
    public void expireDue() {
        var due = jdbc.query("""
                SELECT run_id, agent_name, env, trace_id
                FROM agent_run
                WHERE status = 'SUSPENDED' AND deadline IS NOT NULL AND deadline <= ?
                """, (rs, row) -> new String[] {rs.getString("run_id"), rs.getString("agent_name"),
                rs.getString("env"), rs.getString("trace_id")}, Timestamp.from(clock.instant()));
        for (var item : due) {
            var updated = jdbc.update("""
                    UPDATE agent_run SET status = 'EXPIRED', updated_at = now()
                    WHERE run_id = ? AND status = 'SUSPENDED'
                    """, item[0]);
            if (updated == 1) {
                audits.append(item[1], item[2], "run.suspend", "high", "denied", item[0], item[3]);
            }
        }
    }

    private boolean applyAnswer(String runId, String text) {
        var row = lock(runId);
        var verdict = ApprovalPolicyEngine.resume(
                row == null ? null : row.status(),
                row == null ? null : row.reason(),
                row == null ? null : row.deadline(),
                row == null ? null : row.suspendedAt(),
                clock.instant());
        return switch (verdict) {
            case NOT_FOUND -> throw new KeelException(ErrorCode.RUN_NOT_FOUND, ErrorCode.RUN_NOT_FOUND.message());
            case NOT_RESUMABLE -> throw new KeelException(ErrorCode.RUN_NOT_RESUMABLE, ErrorCode.RUN_NOT_RESUMABLE.message());
            case DENIED -> throw new KeelException(ErrorCode.RUN_RESUME_DENIED, ErrorCode.RUN_RESUME_DENIED.message());
            case EXPIRED -> {
                jdbc.update("UPDATE agent_run SET status = 'EXPIRED', updated_at = now() WHERE run_id = ? AND status = 'SUSPENDED'", runId);
                audits.append(row.agent(), row.env(), "run.suspend", "high", "denied", runId, row.traceId());
                yield true;
            }
            case ALLOW -> {
                audits.append(row.agent(), row.env(), "run.resume", "high", "approved", runId, row.traceId());
                callResume(row.agent(), runId, row.token(), text);
                var updated = jdbc.update("""
                        UPDATE agent_run
                        SET status = 'DONE', resumed_count = resumed_count + 1, updated_at = now()
                        WHERE run_id = ? AND status = 'SUSPENDED'
                        """, runId);
                if (updated != 1) {
                    throw new KeelException(ErrorCode.RUN_NOT_RESUMABLE, ErrorCode.RUN_NOT_RESUMABLE.message());
                }
                yield false;
            }
        };
    }

    private void callResume(String agent, String runId, String token, String text) {
        String endpoint;
        try {
            endpoint = registry.invokeEndpoint(agent);
        } catch (KeelException e) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, ErrorCode.GW_AGENT_OFFLINE.message());
        }
        try {
            agents.resume(endpoint, runId, token == null ? "" : token, null, text);
        } catch (RuntimeException e) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, ErrorCode.GW_AGENT_OFFLINE.message());
        }
    }

    private RunRow lock(String runId) {
        var rows = jdbc.query(selectSql() + " WHERE run_id = ? FOR UPDATE", this::mapRow, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private SuspendedRunView load(String runId) {
        var rows = jdbc.query(selectSql() + " WHERE run_id = ?", this::map, runId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.getFirst();
    }

    private SuspendedRunView map(ResultSet rs, int row) throws SQLException {
        return mapRow(rs, row).view();
    }

    private RunRow mapRow(ResultSet rs, int row) throws SQLException {
        var view = new SuspendedRunView(rs.getString("run_id"), rs.getString("agent_name"), rs.getString("env"),
                rs.getString("trace_id"), rs.getString("suspend_reason"), rs.getString("prompt"),
                rs.getString("actor_user"), offset(rs, "created_at"), offset(rs, "deadline"));
        return new RunRow(rs.getString("status"), view.reason(), view.agent(), view.env(), view.traceId(),
                instant(rs, "deadline"), instant(rs, "created_at"), rs.getString("resume_token"), view);
    }

    private static String selectSql() {
        return """
                SELECT run_id, agent_name, env, trace_id, status, suspend_reason, prompt, actor_user,
                       created_at, deadline, resume_token
                FROM agent_run
                """;
    }

    private boolean agentExists(String name) {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM agent WHERE name = ? AND kind = 'AGENT'", Long.class, name);
        return count != null && count > 0;
    }

    private Instant deadline(String raw, Instant now) {
        if (raw == null || raw.isBlank()) {
            return now.plus(DEFAULT_DEADLINE);
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
        var value = instant(rs, column);
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw invalid();
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static KeelException invalid() {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
    }

    private static KeelException notFound() {
        return new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
    }

    private record RunRow(String status, String reason, String agent, String env, String traceId, Instant deadline,
                          Instant suspendedAt, String token, SuspendedRunView view) {}
}
