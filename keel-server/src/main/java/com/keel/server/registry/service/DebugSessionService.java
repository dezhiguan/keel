package com.keel.server.registry.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Console debug sessions. One owner, one agent, one environment. */
@Service
public class DebugSessionService {
    static final int PAGE = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public DebugSessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String requireOwner() {
        var principal = ConsolePrincipal.current();
        if (principal == null || principal.readOnly() || principal.userId() == null || principal.userId().isBlank()) {
            throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
        }
        return principal.userId();
    }

    public Map<String, Object> list(String agent, String env, String owner, String cursor) {
        jdbc.update("DELETE FROM session_message WHERE expires_at <= now()");
        var policy=jdbc.query("SELECT retention_days FROM agent_history_policy WHERE agent_name=? AND env=?",
                (rs,n)->rs.getObject(1,Integer.class),agent,env);
        var days=policy.isEmpty()?Integer.valueOf(7):policy.getFirst();
        var since = Timestamp.from(days==null?Instant.parse("1970-01-01T00:00:00Z"):Instant.now().minus(days, ChronoUnit.DAYS));
        var total = jdbc.queryForObject("""
                SELECT count(*) FROM agent_session
                WHERE owner_user = ? AND agent_name = ? AND env = ? AND updated_at >= ?
                """, Long.class, owner, agent, env, since);
        List<Map<String, Object>> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = jdbc.query("""
                    SELECT id, title, updated_at,
                           (SELECT count(*) FROM session_message m
                            WHERE m.session_id = agent_session.id AND m.expires_at > now()) AS messages
                    FROM agent_session
                    WHERE owner_user = ? AND agent_name = ? AND env = ? AND updated_at >= ?
                    ORDER BY updated_at DESC, id DESC
                    LIMIT ?
                    """, (rs, n) -> sessionRow(rs), owner, agent, env, since, PAGE);
        } else {
            var mark = cursor(cursor);
            rows = jdbc.query("""
                    SELECT id, title, updated_at,
                           (SELECT count(*) FROM session_message m
                            WHERE m.session_id = agent_session.id AND m.expires_at > now()) AS messages
                    FROM agent_session
                    WHERE owner_user = ? AND agent_name = ? AND env = ? AND updated_at >= ?
                      AND (updated_at < ? OR (updated_at = ? AND id < ?))
                    ORDER BY updated_at DESC, id DESC
                    LIMIT ?
                    """, (rs, n) -> sessionRow(rs), owner, agent, env, since, mark.at(), mark.at(), mark.id(), PAGE);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("retention",days==null?"保留至删除":days+" 天");
        body.put("crossSessionHistory",!policy.isEmpty());
        body.put("items", rows);
        body.put("total", total == null ? 0 : total);
        body.put("nextCursor", rows.size() < PAGE ? null : cursorOf(rows.get(rows.size() - 1)));
        return body;
    }

    public Map<String, Object> create(String agent, String env, String owner) {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        var id = "ss_" + HexFormat.of().formatHex(bytes);
        jdbc.update("""
                INSERT INTO agent_session (id, agent_name, env, owner_user, title, updated_at)
                VALUES (?, ?, ?, ?, '', now())
                """, id, agent, env, owner);
        return Map.of("id", id, "title", "", "messageCount", 0, "updatedAt", Instant.now().toString());
    }

    public void delete(String agent, String owner, String id) {
        require(agent, null, owner, id);
        jdbc.update("DELETE FROM session_message WHERE session_id = ? AND owner_user = ? AND agent_name = ?", id, owner, agent);
        jdbc.update("DELETE FROM agent_session WHERE id = ? AND owner_user = ? AND agent_name = ?", id, owner, agent);
    }

    public Map<String, Object> messages(String agent, String owner, String id, Long before) {
        require(agent, null, owner, id);
        List<Map<String, Object>> rows = before == null
                ? jdbc.query("""
                        SELECT id, role, content, trace_id FROM session_message
                        WHERE session_id = ? AND owner_user = ? AND agent_name = ? AND expires_at > now()
                        ORDER BY id DESC LIMIT ?
                        """, (rs, n) -> messageRow(rs), id, owner, agent, PAGE)
                : jdbc.query("""
                        SELECT id, role, content, trace_id FROM session_message
                        WHERE session_id = ? AND owner_user = ? AND agent_name = ? AND expires_at > now() AND id < ?
                        ORDER BY id DESC LIMIT ?
                        """, (rs, n) -> messageRow(rs), id, owner, agent, before, PAGE);
        var ordered = new ArrayList<Map<String, Object>>();
        for (var i = rows.size() - 1; i >= 0; i--) ordered.add(rows.get(i));
        var body = new LinkedHashMap<String, Object>();
        body.put("items", ordered);
        body.put("nextCursor", rows.size() < PAGE ? null : rows.get(rows.size() - 1).get("id"));
        return body;
    }

    /** Recent turns already stored for this session, oldest first. The agent process does not share them across replicas. */
    public List<Map<String, String>> history(String agent, String owner, String id) {
        var rows = jdbc.query("""
                SELECT id, role, content FROM session_message
                WHERE session_id = ? AND owner_user = ? AND agent_name = ? AND expires_at > now()
                ORDER BY id DESC LIMIT 128
                """, (rs, n) -> Map.of("id", rs.getString("id"), "role", rs.getString("role"), "content", rs.getString("content")), id, owner, agent);
        var ordered = new ArrayList<Map<String, String>>();
        for (var i = rows.size() - 1; i >= 0; i--) {
            var row = rows.get(i);
            ordered.add(Map.of("id", row.get("id"), "role", row.get("role"), "content", row.get("content")));
        }
        return ordered;
    }

    public void require(String agent, String env, String owner, String id) {
        Long found = env == null
                ? jdbc.queryForObject("""
                        SELECT count(*) FROM agent_session WHERE id = ? AND agent_name = ? AND owner_user = ?
                        """, Long.class, id, agent, owner)
                : jdbc.queryForObject("""
                        SELECT count(*) FROM agent_session
                        WHERE id = ? AND agent_name = ? AND env = ? AND owner_user = ?
                        """, Long.class, id, agent, env, owner);
        if (found == null || found == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "会话不存在");
        }
    }

    @Transactional
    public void appendTurn(String agent, String owner, String id, String userText, String answer, String traceId) {
        var policy=jdbc.query("SELECT p.retention_days FROM agent_history_policy p JOIN agent_session s ON s.agent_name=p.agent_name AND s.env=p.env WHERE s.id=? AND s.agent_name=? AND s.owner_user=?",
                (rs,n)->rs.getObject(1,Integer.class),id,agent,owner);
        var days=policy.isEmpty()?Integer.valueOf(7):policy.getFirst();
        var expires = days==null ? new Timestamp(9223372036825200000L /* PostgreSQL JDBC positive infinity */) : Timestamp.from(Instant.now().plus(days, ChronoUnit.DAYS));
        var user = clip(userText);
        var reply = clip(answer);
        jdbc.update("""
                INSERT INTO session_message (session_id, agent_name, owner_user, role, content, trace_id, expires_at)
                VALUES (?, ?, ?, 'user', ?, NULL, ?)
                """, id, agent, owner, user, expires);
        jdbc.update("""
                INSERT INTO session_message (session_id, agent_name, owner_user, role, content, trace_id, expires_at)
                VALUES (?, ?, ?, 'assistant', ?, ?, ?)
                """, id, agent, owner, reply, traceId, expires);
        jdbc.update("""
                UPDATE agent_session
                SET title = CASE WHEN title = '' THEN ? ELSE title END, updated_at = now()
                WHERE id = ? AND owner_user = ? AND agent_name = ?
                """, title(user), id, owner, agent);
    }

    private static Map<String, Object> sessionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", rs.getString("id"));
        row.put("title", rs.getString("title"));
        row.put("messageCount", rs.getInt("messages"));
        row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        return row;
    }

    private static Map<String, Object> messageRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", rs.getLong("id"));
        row.put("role", "assistant".equals(rs.getString("role")) ? "agent" : "user");
        row.put("text", rs.getString("content"));
        row.put("traceId", rs.getString("trace_id"));
        return row;
    }

    private static String cursorOf(Map<String, Object> row) {
        var at = Instant.parse(String.valueOf(row.get("updatedAt"))).toEpochMilli();
        return at + "|" + row.get("id");
    }

    private static Cursor cursor(String raw) {
        var split = raw.indexOf('|');
        if (split < 1) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "cursor 格式不正确");
        try {
            return new Cursor(Timestamp.from(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, split)))), raw.substring(split + 1));
        } catch (NumberFormatException error) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "cursor 格式不正确");
        }
    }

    private static String title(String text) {
        var trimmed = text.trim();
        return trimmed.codePoints().limit(14).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text;
    }

    private record Cursor(Timestamp at, String id) {}
}
