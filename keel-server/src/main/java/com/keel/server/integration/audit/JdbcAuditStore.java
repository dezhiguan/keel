package com.keel.server.integration.audit;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class JdbcAuditStore implements AuditStore {
    private static final Set<String> PAYLOAD_KEYS = Set.of("version", "sha256", "diffLines", "gateRunId", "gitSha", "kind");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String agent, String env, String action, String risk, String decision, String resource, String traceId) {
        append(agent, env, action, risk, decision, resource, traceId, "keel", Map.of());
    }

    @Override
    public void append(String agent, String env, String action, String risk, String decision, String resource,
                       String traceId, String actor, Map<String, Object> payload) {
        var normalizedRisk = risk.toLowerCase(Locale.ROOT);
        var prev = jdbc.query("""
                SELECT hash FROM console_audit_event WHERE agent = ? ORDER BY ts DESC, event_id DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), agent);
        var prevHash = prev.isEmpty() ? "" : prev.getFirst();
        var hash = AuditChain.hash(prevHash, agent, env, action, normalizedRisk, decision, resource, traceId);
        var ts = Instant.now();
        var safePayload = new LinkedHashMap<String, Object>();
        if (payload != null) {
            payload.forEach((key, value) -> {
                if (PAYLOAD_KEYS.contains(key) && value != null && !(value instanceof String text && text.length() > 200)) {
                    safePayload.put(key, value);
                }
            });
        }
        String payloadJson;
        try {
            payloadJson = json.writeValueAsString(safePayload);
        } catch (Exception e) {
            throw new KeelException(ErrorCode.AUDIT_WRITE_FAILED, ErrorCode.AUDIT_WRITE_FAILED.message());
        }
        var actorUser = actor == null || actor.isBlank() ? "keel" : actor;
        var updated = jdbc.update("""
                INSERT INTO console_audit_event
                    (event_id, agent, env, ts, action, risk, decision, resource, trace_id, actor_user, payload, input_digest, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, UUID.randomUUID().toString(), agent, env, Timestamp.from(ts), action, normalizedRisk, decision,
                resource, traceId, actorUser, payloadJson, AuditChain.sha256(action + ":" + agent), prevHash, hash);
        if (updated != 1) {
            throw new KeelException(ErrorCode.AUDIT_WRITE_FAILED, ErrorCode.AUDIT_WRITE_FAILED.message());
        }
    }

    @Override
    public Map<String, Object> page(String agent, String risk, String env, int page, int size) {
        return page(agent, risk, env, "", "", page, size);
    }

    @Override
    public Map<String, Object> page(String agent, String risk, String env, String action, String kind, int page, int size) {
        var agentFilter = agent == null ? "" : agent;
        var riskFilter = risk == null ? "" : risk.toLowerCase(Locale.ROOT);
        var envFilter = env == null || env.isBlank() || "all".equals(env) ? "" : env;
        var actionFilter = action == null ? "" : action;
        var kindFilter = kind == null ? "" : kind;
        var total = jdbc.queryForObject("""
                SELECT count(*) FROM console_audit_event
                WHERE (? = '' OR agent = ?) AND (? = '' OR risk = ?) AND (? = '' OR env = ?)
                  AND (? = '' OR action = ?) AND (? = '' OR payload->>'kind' = ?)
                """, Integer.class, agentFilter, agentFilter, riskFilter, riskFilter, envFilter, envFilter,
                actionFilter, actionFilter, kindFilter, kindFilter);
        var rows = jdbc.query("""
                SELECT event_id, agent, env, ts, action, risk, decision, resource, trace_id, actor_user, payload::text AS payload, input_digest, prev_hash, hash
                FROM console_audit_event
                WHERE (? = '' OR agent = ?) AND (? = '' OR risk = ?) AND (? = '' OR env = ?)
                  AND (? = '' OR action = ?) AND (? = '' OR payload->>'kind' = ?)
                ORDER BY ts DESC, event_id DESC
                LIMIT ? OFFSET ?
                """, (rs, n) -> {
            var item = new LinkedHashMap<String, Object>();
            var storedPrev = rs.getString("prev_hash");
            var storedHash = rs.getString("hash");
            var expected = AuditChain.hash(storedPrev, rs.getString("agent"), rs.getString("env"), rs.getString("action"),
                    rs.getString("risk"), rs.getString("decision"), rs.getString("resource"), rs.getString("trace_id"));
            item.put("eventId", rs.getString("event_id"));
            item.put("ts", rs.getTimestamp("ts").toInstant().toString());
            item.put("agent", rs.getString("agent"));
            item.put("env", rs.getString("env"));
            item.put("traceId", rs.getString("trace_id"));
            item.put("actor", Map.of("userId", rs.getString("actor_user")));
            item.put("action", rs.getString("action"));
            item.put("resource", rs.getString("resource"));
            item.put("risk", rs.getString("risk").toUpperCase(Locale.ROOT));
            item.put("decision", rs.getString("decision"));
            item.put("approver", null);
            item.put("payload", payloadOf(rs.getString("payload")));
            item.put("inputDigest", rs.getString("input_digest"));
            item.put("hashVerified", expected.equals(storedHash));
            item.put("hash", storedHash);
            item.put("prevHash", storedPrev == null || storedPrev.isEmpty() ? null : storedPrev);
            return item;
        }, agentFilter, agentFilter, riskFilter, riskFilter, envFilter, envFilter,
                actionFilter, actionFilter, kindFilter, kindFilter, size, Math.max(0, (page - 1) * size));
        var data = new LinkedHashMap<String, Object>();
        data.put("page", page);
        data.put("size", size);
        data.put("total", total == null ? 0 : total);
        data.put("items", rows);
        return data;
    }

    @Override
    public Map<String, Object> verify(String agent) {
        long started = System.currentTimeMillis();
        var agentFilter = agent == null ? "" : agent;
        var rows = jdbc.query("""
                SELECT event_id, agent, env, action, risk, decision, resource, trace_id, prev_hash, hash
                FROM console_audit_event
                WHERE (? = '' OR agent = ?)
                ORDER BY agent, ts, event_id
                """, (rs, n) -> new String[] {
                rs.getString("event_id"), rs.getString("agent"), rs.getString("env"), rs.getString("action"),
                rs.getString("risk"), rs.getString("decision"), rs.getString("resource"), rs.getString("trace_id"),
                rs.getString("prev_hash"), rs.getString("hash")
        }, agentFilter, agentFilter);
        String brokenAt = null;
        var prevByAgent = new LinkedHashMap<String, String>();
        for (var row : rows) {
            var expectedPrev = prevByAgent.getOrDefault(row[1], "");
            var expected = AuditChain.hash(expectedPrev, row[1], row[2], row[3], row[4], row[5], row[6], row[7]);
            if (!expectedPrev.equals(row[8] == null ? "" : row[8]) || !expected.equals(row[9])) {
                brokenAt = row[0];
                break;
            }
            prevByAgent.put(row[1], row[9]);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("checked", rows.size());
        data.put("intact", brokenAt == null);
        data.put("brokenAt", brokenAt);
        data.put("elapsedMs", System.currentTimeMillis() - started);
        return data;
    }

    private Map<String, Object> payloadOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            var node = json.readTree(raw);
            var payload = new LinkedHashMap<String, Object>();
            node.fields().forEachRemaining(entry -> {
                if (!PAYLOAD_KEYS.contains(entry.getKey())) {
                    return;
                }
                var value = entry.getValue();
                if (value.isNumber()) {
                    payload.put(entry.getKey(), value.numberValue());
                } else if (!value.isNull()) {
                    payload.put(entry.getKey(), value.asText());
                }
            });
            return payload;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
