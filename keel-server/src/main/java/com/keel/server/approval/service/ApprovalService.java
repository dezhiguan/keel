package com.keel.server.approval.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.approval.model.ApprovalView;
import com.keel.server.approval.model.OpenApproval;
import com.keel.server.common.KeelException;
import com.keel.server.common.PageResult;
import com.keel.server.common.TraceIds;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.registry.service.AgentRegistryService;
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
import java.util.Set;
import java.util.UUID;

@Service
public class ApprovalService {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(24);
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "EXPIRED");
    private static final String PLATFORM_AGENT = "keel";

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final AuditStore audits;
    private final java.time.Clock clock;
    private final AgentRegistryService registry;
    private final AgentEndpointClient agents;
    private final TransactionTemplate transactions;

    public ApprovalService(org.springframework.jdbc.core.JdbcTemplate jdbc, AuditStore audits, java.time.Clock clock,
                           AgentRegistryService registry, AgentEndpointClient agents, PlatformTransactionManager tx) {
        this.jdbc = jdbc;
        this.audits = audits;
        this.clock = clock;
        this.registry = registry;
        this.agents = agents;
        this.transactions = new TransactionTemplate(tx);
    }

    @Transactional
    public PageResult<ApprovalView> page(String status, String agent, String env, int page, int size) {
        if (page < 1 || (size != 10 && size != 20 && size != 50 && size != 100)) {
            throw invalid();
        }
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw invalid();
        }
        expireDue();
        var statusFilter = status == null ? "" : status;
        var agentFilter = agent == null ? "" : agent;
        var envFilter = ApprovalPolicyEngine.scope(env);
        var total = jdbc.queryForObject("""
                SELECT count(*) FROM approval_request
                WHERE (? = '' OR status = ?) AND (? = '' OR agent_name = ?) AND (? = '' OR env = ?)
                """, Long.class, statusFilter, statusFilter, agentFilter, agentFilter, envFilter, envFilter);
        var items = jdbc.query(selectSql() + """
                 WHERE (? = '' OR r.status = ?) AND (? = '' OR r.agent_name = ?) AND (? = '' OR r.env = ?)
                 ORDER BY r.created_at DESC, r.id DESC
                 LIMIT ? OFFSET ?
                """, this::map, statusFilter, statusFilter, agentFilter, agentFilter, envFilter, envFilter, size, (page - 1L) * size);
        return new PageResult<>(page, size, total == null ? 0 : total, items);
    }

    public int pendingCount(String env) {
        var envFilter = ApprovalPolicyEngine.scope(env);
        var total = jdbc.queryForObject(
                "SELECT count(*) FROM approval_request WHERE status = 'PENDING' AND (? = '' OR env = ?)",
                Long.class, envFilter, envFilter);
        return total == null ? 0 : Math.toIntExact(total);
    }

    @Transactional
    public ApprovalView open(OpenApproval request) {
        if (request == null) {
            throw invalid();
        }
        ApprovalPolicyEngine.checkSubject(request.subjectType());
        var subjectRef = required(request.subjectRef(), 128);
        var summary = required(request.summary(), 4000);
        var actor = required(request.actorUser(), 128);
        var risk = ApprovalPolicyEngine.risk(request.risk());
        var env = ApprovalPolicyEngine.env(request.env());
        var now = clock.instant();
        var policy = policy(request.policyName(), request.subjectType(), subjectRef);
        if (policy != null && ApprovalPolicyEngine.inCooldown(policy.latestDecidedAt(), policy.cooldownHours(), now)) {
            audit(agentOrPlatform(policy.agentName()), env, risk, "approved", subjectRef, policy.traceId());
            return policy.previous();
        }
        var agent = blankToNull(request.agent());
        if (agent != null && !agentExists(agent)) {
            throw notFound();
        }
        var runId = blankToNull(request.runId());
        if (runId != null) {
            if (!ApprovalPolicyEngine.runId(runId)) {
                throw invalid();
            }
            var runAgent = runAgent(runId);
            if (runAgent == null) {
                throw notFound();
            }
            if (agent != null && !agent.equals(runAgent)) {
                throw invalid();
            }
            agent = runAgent;
        }
        var consent = ApprovalPolicyEngine.consentId(request.consentId());
        if (consent != null) {
            if (runId == null) {
                throw invalid();
            }
            jdbc.update("UPDATE agent_run SET consent_id = ? WHERE run_id = ? AND consent_id IS NULL", consent, runId);
        }
        var expires = now.plus(policy == null ? DEFAULT_TIMEOUT : Duration.ofMinutes(policy.timeoutMinutes()));
        var traceId = firstNonBlank(request.traceId(), TraceIds.current());
        audit(agentOrPlatform(agent), env, risk, "pending", subjectRef, traceId);
        var id = jdbc.queryForObject("""
                INSERT INTO approval_request
                    (subject_type, subject_ref, agent_name, run_id, trace_id, actor_user, payload_digest,
                     summary, status, risk, policy_id, expires_at, env)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, request.subjectType(), subjectRef, agent, runId, traceId, actor,
                clip(request.payloadDigest(), 64), summary, risk, policy == null ? null : Long.valueOf(policy.id()),
                Timestamp.from(expires), env);
        return load(id);
    }

    /**
     * Applies the decision and commits before returning an error for a row that was just expired.
     * TODO(P0-5): decidedBy comes from the auth-gateway JWT, not the fixed local admin.
     */
    public ApprovalView decide(String rawId, String decision, String decidedBy) {
        var result = transactions.execute(status -> applyDecide(rawId, decision, decidedBy));
        if (result == null) {
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, ErrorCode.SERVER_INTERNAL_ERROR.message());
        }
        if (result.error() != null) {
            throw new KeelException(result.error(), result.message());
        }
        return result.view();
    }

    @Transactional
    public void expireDue() {
        var due = jdbc.query("""
                SELECT id, run_id, agent_name, risk, subject_ref, trace_id, env
                FROM approval_request
                WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= ?
                """, (rs, row) -> new Due(rs.getLong("id"), rs.getString("run_id"), rs.getString("agent_name"),
                rs.getString("risk"), rs.getString("subject_ref"), rs.getString("trace_id"), rs.getString("env")),
                Timestamp.from(clock.instant()));
        for (var item : due) {
            if (!mark(item.id(), "EXPIRED", PLATFORM_AGENT)) {
                continue;
            }
            expireRun(item.runId(), "EXPIRED", 0);
            audit(agentOrPlatform(item.agent()), item.env(), item.risk().toLowerCase(), "denied", item.subjectRef(), item.traceId());
        }
    }

    private DecideResult applyDecide(String rawId, String decision, String decidedBy) {
        var row = lock(ApprovalPolicyEngine.parseId(rawId));
        if (row == null) {
            throw notFound();
        }
        var verdict = ApprovalPolicyEngine.decide(row.status(), row.expiresAt(), decision, clock.instant());
        return switch (verdict) {
            case CLOSED -> throw new KeelException(ErrorCode.APPROVAL_EXPIRED, "单子已过期或已被处理");
            case INVALID -> throw invalid();
            case EXPIRE -> {
                if (!mark(row.numericId(), "EXPIRED", decidedBy)) {
                    throw new KeelException(ErrorCode.APPROVAL_EXPIRED, "单子已过期或已被处理");
                }
                expireRun(row.runId(), "EXPIRED", 0);
                audit(agentOrPlatform(row.agent()), "prod", row.risk().toLowerCase(), "denied", row.subjectRef(), row.traceId());
                yield new DecideResult(load(row.numericId()), ErrorCode.APPROVAL_EXPIRED, ErrorCode.APPROVAL_EXPIRED.message());
            }
            case REJECT -> reject(row, decidedBy);
            case APPROVE -> approve(row, decidedBy);
        };
    }

    private DecideResult reject(ApprovalRow row, String decidedBy) {
        audit(agentOrPlatform(row.agent()), "prod", row.risk().toLowerCase(), "rejected", row.subjectRef(), row.traceId());
        if (row.runId() != null) {
            expireRun(row.runId(), "FAILED", 0);
        }
        if (!mark(row.numericId(), "REJECTED", decidedBy)) {
            throw new KeelException(ErrorCode.APPROVAL_EXPIRED, "单子已过期或已被处理");
        }
        return new DecideResult(load(row.numericId()), null, null);
    }

    private DecideResult approve(ApprovalRow row, String decidedBy) {
        var now = clock.instant();
        var cred = row.runId() == null ? null : credential(row.runId());
        var suspendedAt = cred == null ? null : cred.suspendedAt();
        var consent = cred == null ? null : cred.consentId();
        if (row.runId() != null && !ApprovalPolicyEngine.resumeWindowOpen(suspendedAt, now)
                && ApprovalPolicyEngine.passConsent(suspendedAt, consent, now) == null) {
            throw new KeelException(ErrorCode.RUN_RESUME_DENIED, ErrorCode.RUN_RESUME_DENIED.message());
        }
        audit(agentOrPlatform(row.agent()), "prod", row.risk().toLowerCase(), "approved", row.subjectRef(), row.traceId());
        if (row.runId() != null) {
            callResume(row.agent(), row.runId(), "approve", null,
                    ApprovalPolicyEngine.passConsent(suspendedAt, consent, now));
            expireRun(row.runId(), "RUNNING", 1);
        }
        if (!mark(row.numericId(), "APPROVED", decidedBy)) {
            throw new KeelException(ErrorCode.APPROVAL_EXPIRED, "单子已过期或已被处理");
        }
        return new DecideResult(load(row.numericId()), null, null);
    }

    private void callResume(String agent, String runId, String decision, String input, String consentId) {
        String endpoint;
        try {
            endpoint = registry.invokeEndpoint(agent);
        } catch (KeelException e) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, ErrorCode.GW_AGENT_OFFLINE.message());
        }
        try {
            agents.resume(endpoint, runId, resumeToken(runId), decision, input, consentId);
        } catch (RuntimeException e) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, ErrorCode.GW_AGENT_OFFLINE.message());
        }
    }

    private RunCredential credential(String runId) {
        var rows = jdbc.query("SELECT created_at, consent_id FROM agent_run WHERE run_id = ?",
                (rs, row) -> new RunCredential(
                        rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                        rs.getString(2)), runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String resumeToken(String runId) {
        var tokens = jdbc.query("SELECT resume_token FROM agent_run WHERE run_id = ?",
                (rs, row) -> rs.getString(1), runId);
        return tokens.isEmpty() || tokens.getFirst() == null ? "" : tokens.getFirst();
    }

    private boolean mark(long id, String status, String decidedBy) {
        return jdbc.update("""
                UPDATE approval_request
                SET status = ?, decided_by = ?, decided_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, status, decidedBy == null || decidedBy.isBlank() ? "dev" : decidedBy,
                Timestamp.from(clock.instant()), id) == 1;
    }

    private void expireRun(String runId, String status, int resumedDelta) {
        if (runId == null) {
            return;
        }
        jdbc.update("""
                UPDATE agent_run
                SET status = ?, resumed_count = resumed_count + ?, updated_at = now()
                WHERE run_id = ? AND status = 'SUSPENDED'
                """, status, resumedDelta, runId);
    }

    private void audit(String agent, String env, String risk, String decision, String resource, String traceId) {
        audits.append(agent, env, "approval", risk, decision, resource, traceId);
    }

    private ApprovalView load(long id) {
        var rows = jdbc.query(selectSql() + " WHERE r.id = ?", this::map, id);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.getFirst();
    }

    private ApprovalRow lock(long id) {
        var rows = jdbc.query(selectSql() + " WHERE r.id = ? FOR UPDATE OF r", this::mapRow, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PolicyHit policy(String name, String subjectType, String subjectRef) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var rows = jdbc.query("""
                SELECT id, timeout_minutes, cooldown_hours
                FROM approval_policy
                WHERE name = ? AND subject_type = ?
                ORDER BY id
                LIMIT 1
                """, (rs, row) -> new long[] {rs.getLong("id"), rs.getInt("timeout_minutes"), rs.getInt("cooldown_hours")},
                name, subjectType);
        if (rows.isEmpty()) {
            throw notFound();
        }
        var hit = rows.getFirst();
        ApprovalView previous = null;
        Instant decidedAt = null;
        if (hit[2] > 0) {
            var prior = jdbc.query(selectSql() + """
                     WHERE r.subject_type = ? AND r.subject_ref = ? AND r.policy_id = ? AND r.status = 'APPROVED'
                     ORDER BY r.decided_at DESC NULLS LAST, r.id DESC
                     LIMIT 1
                    """, this::mapRow, subjectType, subjectRef, hit[0]);
            if (!prior.isEmpty()) {
                previous = prior.getFirst().view();
                decidedAt = prior.getFirst().decidedAt();
            }
        }
        return new PolicyHit(hit[0], (int) hit[1], (int) hit[2], previous, decidedAt);
    }

    private boolean agentExists(String name) {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM agent WHERE name = ? AND kind = 'AGENT'", Long.class, name);
        return count != null && count > 0;
    }

    private String runAgent(String runId) {
        var names = jdbc.query("SELECT agent_name FROM agent_run WHERE run_id = ?",
                (rs, row) -> rs.getString(1), runId);
        return names.isEmpty() ? null : names.getFirst();
    }

    private ApprovalView map(ResultSet rs, int row) throws SQLException {
        return mapRow(rs, row).view();
    }

    private ApprovalRow mapRow(ResultSet rs, int row) throws SQLException {
        var subjectType = rs.getString("subject_type");
        var subjectRef = rs.getString("subject_ref");
        var id = rs.getLong("id");
        return new ApprovalRow(id, subjectType, subjectRef, rs.getString("run_id"), rs.getString("agent_name"),
                rs.getString("risk"), rs.getString("trace_id"), rs.getString("status"), instant(rs, "expires_at"),
                instant(rs, "decided_at"), new ApprovalView(
                ApprovalPolicyEngine.publicId(id), subjectType, subjectRef, rs.getString("run_id"),
                rs.getString("policy_name"), rs.getString("agent_name"),
                subjectType.startsWith("tool.") ? subjectRef : null, rs.getString("risk"), rs.getString("trace_id"),
                rs.getString("actor_user"), rs.getString("summary"), rs.getString("payload_digest"),
                rs.getString("status"), offset(rs, "created_at"), offset(rs, "expires_at"),
                rs.getString("decided_by"), offset(rs, "decided_at")));
    }

    private static String selectSql() {
        return """
                SELECT r.id, r.subject_type, r.subject_ref, r.agent_name, r.run_id, r.trace_id, r.actor_user,
                       r.payload_digest, r.summary, r.status, r.risk, r.expires_at, r.decided_by, r.decided_at,
                       r.created_at, p.name AS policy_name
                FROM approval_request r
                LEFT JOIN approval_policy p ON p.id = r.policy_id
                """;
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

    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.length() > max) {
            throw invalid();
        }
        return trimmed;
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
        return newTrace();
    }

    private static String newTrace() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String agentOrPlatform(String agent) {
        return agent == null || agent.isBlank() ? PLATFORM_AGENT : agent;
    }

    private static KeelException invalid() {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
    }

    private static KeelException notFound() {
        return new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
    }

    private record DecideResult(ApprovalView view, ErrorCode error, String message) {}

    private record ApprovalRow(long numericId, String subjectType, String subjectRef, String runId, String agent,
                               String risk, String traceId, String status, Instant expiresAt, Instant decidedAt,
                               ApprovalView view) {}

    private record PolicyHit(long id, int timeoutMinutes, int cooldownHours, ApprovalView previous, Instant latestDecidedAt,
                             String agentName, String subjectRef, String traceId) {
        PolicyHit(long id, int timeoutMinutes, int cooldownHours, ApprovalView previous, Instant latestDecidedAt) {
            this(id, timeoutMinutes, cooldownHours, previous, latestDecidedAt,
                    previous == null ? null : previous.agent(),
                    previous == null ? null : previous.subjectRef(),
                    previous == null ? null : previous.traceId());
        }
    }

    private record Due(long id, String runId, String agent, String risk, String subjectRef, String traceId, String env) {}

    private record RunCredential(Instant suspendedAt, String consentId) {}

}
