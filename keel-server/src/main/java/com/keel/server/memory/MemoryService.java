package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.auth.ConsolePrincipal;
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
        authorize(agent, row.ownerOrg(), owner, true);
        MemoryPolicy.requireWritable(row.layer(), agent, body.path("evalRunId").asText(""));
        var embedding = body.get("embedding") == null || body.get("embedding").isNull() ? null : body.get("embedding").toString();
        var id = jdbc.queryForObject("""
                INSERT INTO user_memory (agent_name, owner_user, content, layer, embedding)
                VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id
                """, Long.class, agent, owner, content, layer, embedding);
        var envs = jdbc.query("SELECT env FROM agent_version WHERE agent_name=? ORDER BY released_at DESC LIMIT 1",
                (rs, n) -> rs.getString(1), agent);
        audit.append(agent, envs.isEmpty() ? "dev" : envs.getFirst(), "memory.write", "low", "allowed", "memory:" + id, null, actor,
                Map.of("count", 1, "category", layer));
        var created = new LinkedHashMap<String, Object>();
        created.put("id", id);
        created.put("ownerUser", owner);
        created.put("layer", layer);
        return created;
    }

    public Counts counts(String agent) {
        long user = 0;
        long task = 0;
        long experience = 0;
        var rows = jdbc.query("SELECT layer, count(*) AS n FROM user_memory WHERE agent_name = ? GROUP BY layer",
                (rs, n) -> Map.entry(rs.getString("layer"), rs.getLong("n")), agent);
        for (var row : rows) {
            switch (row.getKey() == null ? "" : row.getKey()) {
                case "TASK" -> task += row.getValue();
                case "EXPERIENCE" -> experience += row.getValue();
                default -> user += row.getValue();
            }
        }
        return new Counts(user, task, experience);
    }

    public Map<String, Object> list(String agent, String ownerUser, String layer) {
        var owner = ownerUser == null ? "" : ownerUser.trim();
        authorize(agent, requireAgent(agent).ownerOrg(), owner, false);
        var wanted = layer == null ? "" : layer.trim();
        var items = jdbc.query("""
                SELECT id, owner_user, content, layer, created_at FROM user_memory
                WHERE agent_name = ? AND (? = '' OR owner_user = ?) AND (? = '' OR layer = ?)
                ORDER BY id
                """, (rs, n) -> row(rs), agent, owner, owner, wanted, wanted);
        return Map.of("items", items);
    }

    public Map<String, Object> recall(String agent, String ownerUser, String layer, List<Double> query) {
        authorize(agent, requireAgent(agent).ownerOrg(), ownerUser, false);
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
        var row = requireAgent(agent);
        var found = jdbc.query("SELECT owner_user, layer FROM user_memory WHERE agent_name = ? AND id = ?",
                (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)), agent, id);
        if (found.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        authorize(agent, row.ownerOrg(), found.getFirst().getKey(), true);
        audit.append(agent, "prod", "memory.delete", "low", "allowed", "memory:" + id, null, actor,
                Map.of("count", 1, "category", found.getFirst().getValue()));
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

    private AgentRow requireAgent(String agent) {
        var row = agent(agent);
        if (row == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        return row;
    }

    static void authorize(String agent, String ownerOrg, String ownerUser, boolean writing) {
        var principal = ConsolePrincipal.current();
        if (principal == null || "PREVIEW".equals(principal.mode())
                || ("SERVICE".equals(principal.mode()) && !agent.equals(principal.userId()))) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
        if ("SERVICE".equals(principal.mode())) {
            if (!writing && (ownerUser == null || ownerUser.isBlank())) {
                throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, "智能体读取记忆时必须指定用户");
            }
            return;
        }
        if (principal.readOnly() || (!"ADMIN".equals(principal.platformRole())
                && !java.util.Objects.equals(ownerOrg, principal.org())
                && (ownerUser == null || !ownerUser.equals(principal.userId())))) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
    }

    private AgentRow agent(String agent) {
        var rows = jdbc.query("SELECT layer, owner_org FROM agent WHERE name = ?",
                (rs, n) -> new AgentRow(rs.getString("layer"), rs.getString("owner_org")), agent);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record AgentRow(String layer, String ownerOrg) {}

    public record Counts(long user, long task, long experience) {}
}
