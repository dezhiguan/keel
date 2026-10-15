package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
        var layer = body.path("layer").asText("USER").trim();
        if (owner.isBlank() || content.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "ownerUser 和 content 都不能为空");
        }
        if (!layer.equals("USER") && !layer.equals("TASK") && !layer.equals("EXPERIENCE")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "记忆层只能是 USER、TASK 或 EXPERIENCE");
        }
        var row = agent(agent);
        if (row == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        MemoryPolicy.requireWritable(row.layer(), agent, body.path("evalRunId").asText(""));
        var embedding = body.get("embedding") == null || body.get("embedding").isNull() ? null : body.get("embedding").toString();
        var id = jdbc.queryForObject("""
                INSERT INTO user_memory (agent_name, owner_user, content, layer, embedding)
                VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id
                """, Long.class, agent, owner, content, layer, embedding);
        var created = new LinkedHashMap<String, Object>();
        created.put("id", id);
        created.put("ownerUser", owner);
        created.put("layer", layer);
        return created;
    }

    public Map<String, Object> list(String agent, String ownerUser, String layer) {
        requireAgent(agent);
        var owner = ownerUser == null ? "" : ownerUser.trim();
        var wanted = layer == null ? "" : layer.trim();
        var items = jdbc.query("""
                SELECT id, owner_user, content, layer, created_at FROM user_memory
                WHERE agent_name = ? AND (? = '' OR owner_user = ?) AND (? = '' OR layer = ?)
                ORDER BY id
                """, (rs, n) -> row(rs), agent, owner, owner, wanted, wanted);
        return Map.of("items", items);
    }

    public Map<String, Object> recall(String agent, String ownerUser, String layer, List<Double> query) {
        requireAgent(agent);
        var rows = jdbc.query("""
                SELECT id, owner_user, content, layer, embedding::text, created_at FROM user_memory
                WHERE agent_name = ? AND owner_user = ? AND layer = ?
                ORDER BY id
                """, (rs, n) -> {
            var item = row(rs);
            item.put("embedding", readEmbedding(rs.getString("embedding")));
            return item;
        }, agent, ownerUser, layer);
        var vectors = new ArrayList<List<Double>>();
        for (var item : rows) {
            @SuppressWarnings("unchecked")
            var vector = (List<Double>) item.get("embedding");
            vectors.add(vector);
        }
        var order = MemoryRecall.nearest(query, vectors);
        var items = new ArrayList<Map<String, Object>>();
        for (var index : order) {
            var item = new LinkedHashMap<>(rows.get(index));
            item.remove("embedding");
            items.add(item);
        }
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

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        var item = new LinkedHashMap<String, Object>();
        item.put("id", rs.getLong("id"));
        item.put("ownerUser", rs.getString("owner_user"));
        item.put("content", rs.getString("content"));
        item.put("layer", rs.getString("layer"));
        item.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        return item;
    }

    private List<Double> readEmbedding(String raw) {
        var values = new ArrayList<Double>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            node.forEach(item -> values.add(item.asDouble()));
        } catch (Exception ignored) {
            return List.of();
        }
        return values;
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
