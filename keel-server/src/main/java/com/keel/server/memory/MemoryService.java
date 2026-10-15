package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryService {
    private final JdbcTemplate jdbc;
    private final AuditStore audit;

    public MemoryService(JdbcTemplate jdbc, AuditStore audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> write(String agent, JsonNode body, String actor) {
        var owner = body.path("ownerUser").asText("").trim();
        var content = body.path("content").asText("").trim();
        if (owner.isBlank() || content.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "ownerUser 和 content 都不能为空");
        }
        var row = agent(agent);
        if (row == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        MemoryPolicy.requireWritable(row.layer(), agent, body.path("evalRunId").asText(""));
        var id = jdbc.queryForObject("""
                INSERT INTO user_memory (agent_name, owner_user, content)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, agent, owner, content);
        return Map.of("id", id, "ownerUser", owner);
    }

    public Map<String, Object> list(String agent, String ownerUser) {
        requireAgent(agent);
        var owner = ownerUser == null ? "" : ownerUser.trim();
        var items = jdbc.query("""
                SELECT id, owner_user, content, created_at FROM user_memory
                WHERE agent_name = ? AND (? = '' OR owner_user = ?)
                ORDER BY id
                """, (rs, n) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", rs.getLong("id"));
            item.put("ownerUser", rs.getString("owner_user"));
            item.put("content", rs.getString("content"));
            item.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return item;
        }, agent, owner, owner);
        return Map.of("items", items);
    }

    public Map<String, Object> delete(String agent, long id, String actor) {
        requireAgent(agent);
        var found = jdbc.query("SELECT id FROM user_memory WHERE agent_name = ? AND id = ?",
                (rs, n) -> rs.getLong(1), agent, id);
        if (found.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        audit.append(agent, "prod", "memory.delete", "low", "allowed", "memory:" + id, null, actor,
                Map.of("count", 1, "category", "USER"));
        jdbc.update("DELETE FROM user_memory WHERE agent_name = ? AND id = ?", agent, id);
        return Map.of("id", id, "items", List.of());
    }

    private void requireAgent(String agent) {
        if (agent(agent) == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
    }

    private AgentRow agent(String agent) {
        var rows = jdbc.query("SELECT layer FROM agent WHERE name = ?",
                (rs, n) -> new AgentRow(rs.getString("layer")), agent);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record AgentRow(String layer) {}
}
