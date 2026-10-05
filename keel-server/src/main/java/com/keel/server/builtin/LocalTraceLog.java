package com.keel.server.builtin;

import com.keel.server.insight.SavedTraces;
import com.keel.server.insight.SuspendedTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
        var filter = agent == null ? "" : agent;
        var total = jdbc.queryForObject("""
                SELECT count(*) FROM invoke_trace WHERE (? = '' OR agent = ?)
                """, Integer.class, filter, filter);
        var rows = jdbc.query("""
                SELECT trace_id, agent, question, started_at, duration_ms, status
                FROM invoke_trace
                WHERE (? = '' OR agent = ?)
                ORDER BY started_at DESC
                LIMIT ? OFFSET ?
                """, (rs, n) -> summary(
                rs.getString("trace_id"),
                rs.getString("agent"),
                rs.getString("question"),
                rs.getTimestamp("started_at").toInstant().toString(),
                rs.getInt("duration_ms"),
                rs.getString("status")), filter, filter, size, Math.max(0, (page - 1) * size));
        var data = new LinkedHashMap<String, Object>();
        data.put("page", page);
        data.put("size", size);
        data.put("total", total == null ? 0 : total);
        data.put("items", rows);
        return data;
    }

    @Override
    public Map<String, Object> detail(String traceId) {
        var rows = jdbc.query("""
                SELECT trace_id, agent, question, started_at, duration_ms, status
                FROM invoke_trace WHERE trace_id = ?
                """, (rs, n) -> new String[] {
                rs.getString("trace_id"), rs.getString("agent"), rs.getString("question"),
                rs.getTimestamp("started_at").toInstant().toString(),
                Integer.toString(rs.getInt("duration_ms")), rs.getString("status")
        }, traceId);
        if (rows.isEmpty()) {
            return suspended(traceId);
        }
        var row = rows.getFirst();
        var summary = summary(row[0], row[1], row[2], row[3], Integer.parseInt(row[4]), row[5]);
        var node = new LinkedHashMap<String, Object>();
        node.put("id", "echo");
        node.put("agentKey", row[1]);
        node.put("type", "agent");
        node.put("name", "echo");
        node.put("startMs", 0);
        node.put("durationMs", Integer.parseInt(row[4]));
        node.put("status", row[5]);
        node.put("depth", 0);
        node.put("inputSummary", "探针");
        node.put("outputSummary", "探针");
        var detail = new LinkedHashMap<String, Object>();
        detail.put("summary", summary);
        detail.put("langfuseUrl", "");
        detail.put("agents", List.of(Map.of("key", row[1], "name", row[1], "color", "#8a97ab", "kind", "sub")));
        detail.put("latencyBreakdown", List.of(Map.of("label", "echo", "ms", Integer.parseInt(row[4]), "agentKey", row[1])));
        detail.put("nodes", List.of(node));
        detail.put("edges", List.of());
        return detail;
    }

    private Map<String, Object> suspended(String traceId) {
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

    private static Map<String, Object> summary(String traceId, String agent, String question, String startedAt, int durationMs, String status) {
        var item = new LinkedHashMap<String, Object>();
        item.put("traceId", traceId);
        item.put("question", question);
        item.put("startedAt", startedAt);
        item.put("rootAgent", agent);
        item.put("agents", new ArrayList<>(List.of(agent)));
        item.put("multiAgent", false);
        item.put("durationMs", durationMs);
        item.put("tokens", 0);
        item.put("costCny", 0);
        item.put("status", status);
        item.put("blocked", false);
        return item;
    }
}
