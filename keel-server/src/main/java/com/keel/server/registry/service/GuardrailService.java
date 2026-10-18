package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Reads the registered manifest and recent guardrail observations for the console guard page. */
@Service
public class GuardrailService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LangfuseClient langfuse;

    public GuardrailService(JdbcTemplate jdbc, ObjectMapper json, LangfuseClient langfuse) {
        this.jdbc = jdbc;
        this.json = json;
        this.langfuse = langfuse;
    }

    public java.util.Map<String, Object> page(String name) {
        var version = latest(name);
        var observations = observations();
        var callers = callers(name);
        var draft = GuardrailPage.view(name, version.version, version.env, instance(name, version.env),
                version.manifest, observations, List.of(), callers);
        var traceId = draft.get("traceId") instanceof String id ? id : "";
        return GuardrailPage.view(name, version.version, version.env, instance(name, version.env),
                version.manifest, observations, trace(traceId), callers);
    }

    public java.util.Map<String, Object> checkTimed(String name, String source, String text) {
        var version = latest(name);
        var started = System.nanoTime();
        java.util.Map<String, Object> result;
        try {
            result = GuardrailPage.check(version.manifest, source, text == null ? "" : text,
                    instance(name, version.env), version.version, 0);
        } catch (IllegalArgumentException ex) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ex.getMessage());
        }
        result.put("latencyMs", Math.max(1, (System.nanoTime() - started) / 1_000_000));
        return result;
    }

    public java.util.Map<String, Object> save(String name, JsonNode body) {
        var version = latest(name);
        if (version.id == 0 || !(version.manifest instanceof ObjectNode root)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "这个智能体还没有登记 agent.yaml");
        }
        try {
            GuardrailPage.apply(root, body);
        } catch (IllegalArgumentException ex) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ex.getMessage());
        }
        try {
            var text = json.writeValueAsString(root);
            jdbc.update("UPDATE agent_version SET manifest_json = ?::jsonb, manifest_hash = ? WHERE id = ?",
                    text, sha256(text), version.id);
        } catch (KeelException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, "无法改写 spec.guardrails");
        }
        return page(name);
    }

    private Version latest(String name) {
        var found = jdbc.queryForObject("SELECT count(*) FROM agent WHERE name = ?", Integer.class, name);
        if (found == null || found == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "智能体不存在");
        }
        var rows = jdbc.query("""
                SELECT id, version, env, manifest_json::text
                FROM agent_version WHERE agent_name = ?
                ORDER BY released_at DESC, id DESC LIMIT 1
                """, (rs, n) -> new Version(rs.getLong(1), rs.getString(2), rs.getString(3), read(rs.getString(4))), name);
        return rows.isEmpty() ? new Version(0, null, null, null) : rows.getFirst();
    }

    private String instance(String name, String env) {
        if (env == null) {
            return null;
        }
        var rows = jdbc.query("""
                SELECT instance_id FROM agent_instance
                WHERE agent_name = ? AND env = ?
                ORDER BY ready DESC, last_seen_at DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), name, env);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<GuardrailPage.Caller> callers(String name) {
        return jdbc.query("""
                SELECT agent_name, env, external_id FROM agent_resource
                WHERE type = 'litellm_key' AND status NOT IN ('BLOCKED', 'REVOKED')
                ORDER BY CASE WHEN agent_name = ? THEN 0 ELSE 1 END, agent_name, env
                LIMIT 12
                """, (rs, n) -> {
            var agent = rs.getString(1);
            var env = rs.getString(2);
            var key = rs.getString(3);
            var self = name.equals(agent);
            return new GuardrailPage.Caller(self ? agent + "（走 ctx.llm）" : agent + " · " + env,
                    key, self ? "sdk" : "unknown");
        }, name);
    }

    private List<JsonNode> observations() {
        try {
            var body = langfuse.guardrailObservations(Instant.now().minus(Duration.ofDays(7)));
            var rows = new ArrayList<JsonNode>();
            body.path("data").forEach(rows::add);
            return rows;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private List<JsonNode> trace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        try {
            var body = langfuse.observationsByTrace(traceId);
            var rows = new ArrayList<JsonNode>();
            body.path("data").forEach(rows::add);
            return rows;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private JsonNode read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Version(long id, String version, String env, JsonNode manifest) {}
}
