package com.keel.server.builtin;

import com.keel.server.insight.SavedTraces;
import com.keel.server.insight.SavedTraces.TraceContext;
import com.keel.server.insight.SavedTraces.TraceHit;
import com.keel.server.insight.SuspendedTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.keel.server.insight.TraceAssembly;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Traces written by the builtin echo probe. Langfuse remains the source when it answers. */
@Service
public class LocalTraceLog implements SavedTraces {
    private final JdbcTemplate jdbc;

    public LocalTraceLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String agent, String env, String traceId, String question, int durationMs) {
        jdbc.update("""
                INSERT INTO invoke_trace (trace_id, agent, env, question, started_at, duration_ms, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ok')
                """, traceId, agent, env, question, Timestamp.from(Instant.now()), durationMs);
    }

    @Override
    public Map<String, Object> list(int page, int size, String agent) {
        return list(page, size, agent, "all");
    }

    @Override
    public Map<String, Object> list(int page, int size, String agent, String env) {
        return list(page, size, agent, env, null, null, null, false, null);
    }

    @Override
    public Map<String, Object> list(int page, int size, String agent, String env, String status,
                                    Instant from, Instant to, boolean multiOnly, Integer minDurationMs) {
        if (multiOnly) {
            return page(page, size, List.of());
        }
        var filter = agent == null ? "" : agent;
        var envFilter = env == null || env.isBlank() || "all".equals(env) ? "" : env;
        var rows = new ArrayList<>(jdbc.query("""
                SELECT trace_id, agent, env, question, started_at, duration_ms, status
                FROM invoke_trace
                WHERE (? = '' OR agent = ?) AND (? = '' OR env = ?)
                ORDER BY started_at DESC
                LIMIT 500
                """, (rs, n) -> summary(
                rs.getString("trace_id"),
                rs.getString("agent"),
                rs.getString("env"),
                rs.getString("question"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getInt("duration_ms"),
                rs.getString("status"),
                null), filter, filter, envFilter, envFilter));
        for (var pending : suspended(agent, env)) {
            var id = String.valueOf(pending.get("traceId"));
            var existing = rows.stream().filter(item -> id.equals(item.get("traceId"))).findFirst();
            if (existing.isPresent()) {
                existing.get().put("pendingReason", pending.get("pendingReason"));
                existing.get().put("runId", pending.get("runId"));
                existing.get().put("humanWaitMs", pending.get("humanWaitMs"));
                existing.get().put("userId", pending.get("userId"));
            } else {
                rows.add(pending);
            }
        }
        var matched = rows.stream()
                .filter(item -> keep(item, status, from, to, minDurationMs))
                .sorted(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.getOrDefault("startedAt", ""))).reversed())
                .toList();
        return page(page, size, matched);
    }

    @Override
    public List<Map<String, Object>> suspended(String agent, String env) {
        var filter = agent == null ? "" : agent;
        var envFilter = env == null || env.isBlank() || "all".equals(env) ? "" : env;
        return jdbc.query("""
                SELECT run_id, agent_name, env, trace_id, prompt, actor_user, suspend_reason, session_id, resumed_count, created_at
                FROM agent_run
                WHERE status = 'SUSPENDED' AND (? = '' OR agent_name = ?) AND (? = '' OR env = ?)
                ORDER BY created_at DESC
                LIMIT 100
                """, (rs, n) -> suspendedSummary(rs.getString("trace_id"), rs.getString("run_id"), rs.getString("agent_name"),
                rs.getString("env"), rs.getString("prompt"), rs.getString("actor_user"), rs.getString("suspend_reason"),
                rs.getString("session_id"), rs.getInt("resumed_count"), rs.getTimestamp("created_at").toInstant()),
                filter, filter, envFilter, envFilter);
    }

    @Override
    public TraceContext context(String traceId) {
        var audits = jdbc.query("SELECT event_id FROM console_audit_event WHERE trace_id = ? ORDER BY ts",
                (rs, n) -> rs.getString("event_id"), traceId);
        var approvals = jdbc.query("""
                SELECT id, summary FROM approval_request
                WHERE trace_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> new String[] {Long.toString(rs.getLong("id")), rs.getString("summary")}, traceId);
        var runs = jdbc.query("""
                SELECT run_id, status, suspend_reason, actor_user, session_id, resumed_count, created_at
                FROM agent_run WHERE trace_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> new RunRow(rs.getString("run_id"), rs.getString("status"), rs.getString("suspend_reason"),
                rs.getString("actor_user"), rs.getString("session_id"), rs.getInt("resumed_count"),
                rs.getTimestamp("created_at").toInstant()), traceId);
        String approvalId = approvals.isEmpty() ? null : "ap_" + approvals.getFirst()[0];
        String pending = null;
        String runId = null;
        String userId = null;
        String sessionId = null;
        Integer suspendCount = null;
        Long wait = null;
        if (!runs.isEmpty()) {
            var run = runs.getFirst();
            runId = run.id();
            userId = run.user();
            sessionId = run.session();
            var open = "SUSPENDED".equals(run.status());
            suspendCount = open ? run.resumed() + 1 : (run.resumed() > 0 ? run.resumed() : null);
            if (open) {
                pending = approvals.isEmpty() ? TraceAssembly.pendingText(run.reason()) : approvals.getFirst()[1];
                wait = Math.max(0, Duration.between(run.created(), Instant.now()).toMillis());
            }
        } else if (!approvals.isEmpty()) {
            pending = approvals.getFirst()[1];
        }
        return new TraceContext(audits, approvalId, pending, runId, suspendCount, userId, sessionId, wait);
    }

    @Override
    public List<TraceHit> since(Instant from, String env) {
        var scoped = env != null && !env.isBlank() && !"all".equals(env) ? env : "";
        return jdbc.query("""
                SELECT trace_id, agent FROM invoke_trace
                WHERE started_at >= ? AND (? = '' OR env = ?)
                """, (rs, n) -> new TraceHit(rs.getString("trace_id"), rs.getString("agent")),
                Timestamp.from(from), scoped, scoped);
    }

    @Override
    public Map<String, Object> detail(String traceId) {
        var rows = jdbc.query("""
                SELECT trace_id, agent, env, question, started_at, duration_ms, status
                FROM invoke_trace WHERE trace_id = ?
                """, (rs, n) -> new Object[] {
                rs.getString("trace_id"), rs.getString("agent"), rs.getString("env"), rs.getString("question"),
                rs.getTimestamp("started_at").toInstant(), rs.getInt("duration_ms"), rs.getString("status")
        }, traceId);
        if (rows.isEmpty()) {
            var suspended = suspendedDetail(traceId);
            if (suspended != null) {
                TraceAssembly.attach(suspended, context(traceId));
            }
            return suspended;
        }
        var row = rows.getFirst();
        var agent = (String) row[1];
        var env = (String) row[2];
        var question = (String) row[3];
        var duration = (Integer) row[5];
        var status = (String) row[6];
        var summary = summary((String) row[0], agent, env, question, (Instant) row[4], duration, status, null);
        var node = new LinkedHashMap<String, Object>();
        node.put("id", "echo");
        node.put("agentKey", agent);
        node.put("type", "agent");
        node.put("name", "echo");
        node.put("service", agent);
        node.put("startMs", 0);
        node.put("durationMs", duration);
        node.put("status", status);
        node.put("depth", 0);
        node.put("criticalPath", true);
        node.put("aggregated", false);
        node.put("inputSummary", question);
        node.put("outputSummary", "echo");
        node.put("auditIds", List.of());
        node.put("costCny", null);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("summary", summary);
        detail.put("agents", List.of(Map.of(
                "key", agent, "name", agent, "subtitle", env + " · 单智能体",
                "color", TraceAssembly.color(agent), "kind", "supervisor")));
        detail.put("latencyBreakdown", List.of(Map.of("label", "echo", "ms", duration, "agentKey", agent)));
        detail.put("nodes", List.of(node));
        detail.put("edges", List.of());
        TraceAssembly.attach(detail, context(traceId));
        return detail;
    }

    private Map<String, Object> suspendedDetail(String traceId) {
        var now = Instant.now();
        var runs = jdbc.query("""
                SELECT run_id, agent_name, prompt, actor_user, status, suspend_reason, created_at
                FROM agent_run WHERE trace_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> SuspendedTrace.fromRun(traceId, rs.getString("run_id"), rs.getString("agent_name"),
                rs.getString("prompt"), rs.getString("actor_user"), rs.getString("status"), rs.getString("suspend_reason"),
                rs.getTimestamp("created_at").toInstant(), now), traceId);
        if (!runs.isEmpty()) {
            return runs.getFirst();
        }
        var approvals = jdbc.query("""
                SELECT id, agent_name, summary, actor_user, subject_ref, created_at
                FROM approval_request WHERE trace_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> SuspendedTrace.fromApproval(traceId, "ap_" + rs.getLong("id"), rs.getString("agent_name"),
                rs.getString("summary"), rs.getString("actor_user"), rs.getString("subject_ref"),
                rs.getTimestamp("created_at").toInstant(), now), traceId);
        return approvals.isEmpty() ? null : approvals.getFirst();
    }

    private static Map<String, Object> summary(String traceId, String agent, String env, String question, Instant startedAt,
                                              int durationMs, String status, String pendingReason) {
        var item = new LinkedHashMap<String, Object>();
        item.put("traceId", traceId);
        item.put("question", question);
        item.put("startedAt", startedAt.toString());
        item.put("env", env);
        item.put("rootAgent", agent);
        item.put("agents", new ArrayList<>(List.of(agent)));
        item.put("multiAgent", false);
        item.put("durationMs", durationMs);
        item.put("status", "fallback".equals(status) || "failed".equals(status) ? status : "ok");
        item.put("blocked", false);
        item.put("cached", false);
        if (pendingReason != null && !pendingReason.isBlank()) {
            item.put("pendingReason", pendingReason);
        }
        return item;
    }

    private static Map<String, Object> suspendedSummary(String traceId, String runId, String agent, String env, String prompt,
                                                        String actor, String reason, String sessionId, int resumed, Instant created) {
        var item = summary(traceId, agent, env, prompt == null || prompt.isBlank() ? TraceAssembly.pendingText(reason) : prompt,
                created, 0, "ok", TraceAssembly.pendingText(reason));
        item.put("runId", runId);
        item.put("userId", actor == null ? "" : actor);
        item.put("suspendCount", resumed + 1);
        item.put("humanWaitMs", Math.max(0L, Duration.between(created, Instant.now()).toMillis()));
        if (sessionId != null && !sessionId.isBlank()) {
            item.put("sessionId", sessionId);
        }
        return item;
    }

    private static boolean keep(Map<String, Object> item, String status, Instant from, Instant to, Integer minDurationMs) {
        if (from != null || to != null) {
            try {
                var started = Instant.parse(String.valueOf(item.get("startedAt")));
                if (from != null && started.isBefore(from)) {
                    return false;
                }
                if (to != null && !started.isBefore(to)) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        if (minDurationMs != null && minDurationMs > 0) {
            var duration = item.get("durationMs");
            if (!(duration instanceof Number number) || number.intValue() < minDurationMs) {
                return false;
            }
        }
        if (status == null || status.isBlank()) {
            return true;
        }
        var pending = item.get("pendingReason") instanceof String text && !text.isBlank();
        return switch (status) {
            case "pending" -> pending;
            case "blocked" -> false;
            case "ok", "fallback", "failed" -> status.equals(item.get("status")) && !pending;
            default -> false;
        };
    }

    private static Map<String, Object> page(int page, int size, List<Map<String, Object>> matched) {
        int from = Math.max(0, (page - 1) * size);
        var data = new LinkedHashMap<String, Object>();
        data.put("page", page);
        data.put("size", size);
        data.put("total", matched.size());
        data.put("items", matched.subList(from, Math.min(matched.size(), from + size)));
        return data;
    }

    private record RunRow(String id, String status, String reason, String user, String session, int resumed, Instant created) {}
}
