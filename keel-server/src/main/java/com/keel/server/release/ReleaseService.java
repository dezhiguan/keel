package com.keel.server.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.prompt.PromptCatalog;
import com.keel.server.prompt.PromptService;
import com.keel.server.prompt.PromptTexts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReleaseService {
    private final PromptCatalog catalog;
    private final LangfuseClient langfuse;
    private final JdbcTemplate jdbc;
    private final AuditStore audit;
    private final PromptService prompts;
    private final ObjectMapper json = new ObjectMapper();

    public ReleaseService(PromptCatalog catalog, LangfuseClient langfuse, JdbcTemplate jdbc, AuditStore audit,
                          PromptService prompts) {
        this.catalog = catalog;
        this.langfuse = langfuse;
        this.jdbc = jdbc;
        this.audit = audit;
        this.prompts = prompts;
    }

    public Map<String, Object> release(String agent, JsonNode body) {
        catalog.authorize(agent);
        var env = body.path("env").asText("");
        var gateRunId = body.path("gateRunId").asText("");
        var image = body.path("image").asText("");
        if (!"prod".equals(env) && !"staging".equals(env)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "env 只能是 staging 或 prod");
        }
        if (gateRunId.isBlank() || image.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "gateRunId 和 image 不能为空");
        }
        ObjectNode promptVersions = json.createObjectNode();
        if ("prod".equals(env)) {
            prompts.invalidateListCache();
            promptVersions = moveProduction(agent, gateRunId);
        }
        var version = agentVersion(agent);
        jdbc.update("""
                INSERT INTO release_record (agent_name, version, env, gate_passed, prompt_versions_json, ci_run_url, kind)
                VALUES (?, ?, ?, true, ?::jsonb, ?, 'release')
                """, agent, version, env, promptVersions.toString(), gateRunId);
        var result = new LinkedHashMap<String, Object>();
        result.put("version", version);
        result.put("env", env);
        result.put("gatePassed", true);
        result.put("image", image);
        result.put("ciRunUrl", gateRunId);
        result.put("releasedBy", PromptCatalog.Actor.current().userId());
        return result;
    }

    public Map<String, Object> rollback(String agent, String prompt, int version, String reason) {
        catalog.authorize(agent);
        var item = catalog.require(agent, prompt);
        if (!released(agent, prompt, version)) {
            throw new KeelException(ErrorCode.PROMPT_ROLLBACK_TARGET, ErrorCode.PROMPT_ROLLBACK_TARGET.message());
        }
        if (!langfuse.ready()) {
            throw new KeelException(ErrorCode.PROMPT_UNAVAILABLE, "Langfuse 未配置");
        }
        var remote = langfuse.getPrompt(item.fullName(), version, null);
        if (remote == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "没有这个提示词版本");
        }
        var type = remote.path("type").asText("text");
        var sha = PromptTexts.sha256(remote.get("prompt"), "chat".equals(type) ? "chat" : "text");
        prompts.invalidateListCache();
        langfuse.moveLabel(item.fullName(), version, "production");
        var versions = latestPromptVersions(agent);
        var entry = json.createObjectNode();
        entry.put("version", version);
        entry.put("sha256", sha);
        versions.set(prompt, entry);
        jdbc.update("""
                INSERT INTO release_record (agent_name, version, env, gate_passed, prompt_versions_json, ci_run_url, kind)
                VALUES (?, ?, 'prod', true, ?::jsonb, ?, 'rollback')
                """, agent, agentVersion(agent), versions.toString(), reason == null ? "" : reason);
        audit.append(agent, "prod", "config.change", "mid", "allowed", "prompt:" + item.fullName(), null,
                PromptCatalog.Actor.current().userId(), Map.of("version", version, "sha256", sha));
        return Map.of("version", version, "env", "prod");
    }

    private ObjectNode moveProduction(String agent, String gateRunId) {
        if (!langfuse.ready()) {
            throw new KeelException(ErrorCode.PROMPT_UNAVAILABLE, "Langfuse 未配置");
        }
        var plan = new ArrayList<Step>();
        for (var item : catalog.declared(agent)) {
            var staging = labelVersion(item.fullName(), "staging");
            var passed = latestPassed(agent, item.name());
            if (staging == null || passed == null || staging != passed.version()) {
                throw new KeelException(ErrorCode.PROMPT_NOT_GATED, ErrorCode.PROMPT_NOT_GATED.message());
            }
            plan.add(new Step(item.name(), item.fullName(), staging, labelVersion(item.fullName(), "production"), passed.sha()));
        }
        var moved = new ArrayList<Step>();
        try {
            for (var step : plan) {
                langfuse.moveLabel(step.fullName(), step.target(), "production");
                moved.add(step);
            }
        } catch (RuntimeException e) {
            revert(moved);
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, "发布提示词中途失败，已把 production 标签挪回");
        }
        var versions = json.createObjectNode();
        for (var step : plan) {
            var entry = versions.putObject(step.name());
            entry.put("version", step.target());
            entry.put("sha256", step.sha());
            audit.append(agent, "prod", "config.change", "mid", "allowed", "prompt:" + step.fullName(), null,
                    PromptCatalog.Actor.current().userId(), Map.of("version", step.target(), "sha256", step.sha(), "gateRunId", gateRunId));
        }
        return versions;
    }

    private void revert(List<Step> moved) {
        for (int i = moved.size() - 1; i >= 0; i--) {
            var step = moved.get(i);
            try {
                if (step.previous() == null) {
                    langfuse.removeLabel(step.fullName(), step.target(), "production");
                } else {
                    langfuse.moveLabel(step.fullName(), step.previous(), "production");
                }
            } catch (RuntimeException ignored) {
                // Keep reverting the remaining labels. The release still fails.
            }
        }
    }

    private boolean released(String agent, String prompt, int version) {
        var rows = jdbc.query("""
                SELECT prompt_versions_json::text FROM release_record
                WHERE agent_name = ? AND env = 'prod' AND prompt_versions_json IS NOT NULL
                """, (rs, n) -> rs.getString(1), agent);
        for (var raw : rows) {
            try {
                if (json.readTree(raw).path(prompt).path("version").asInt(-1) == version) {
                    return true;
                }
            } catch (Exception ignored) {
                // Skip a row that is not the expected object.
            }
        }
        return false;
    }

    private ObjectNode latestPromptVersions(String agent) {
        var rows = jdbc.query("""
                SELECT prompt_versions_json::text FROM release_record
                WHERE agent_name = ? AND env = 'prod' AND prompt_versions_json IS NOT NULL
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), agent);
        if (rows.isEmpty()) {
            return json.createObjectNode();
        }
        try {
            var node = json.readTree(rows.getFirst());
            return node.isObject() ? (ObjectNode) node : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private Passed latestPassed(String agent, String prompt) {
        var rows = jdbc.query("""
                SELECT version, sha256 FROM prompt_promotion
                WHERE agent_name = ? AND prompt_name = ? AND gate_status = 'PASSED'
                ORDER BY promoted_at DESC, id DESC LIMIT 1
                """, (rs, n) -> new Passed(rs.getInt(1), rs.getString(2)), agent, prompt);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Integer labelVersion(String fullName, String label) {
        var body = langfuse.getPrompt(fullName, null, label);
        if (body == null || !body.path("version").canConvertToInt()) {
            return null;
        }
        return body.path("version").asInt();
    }

    private String agentVersion(String agent) {
        var rows = jdbc.query("""
                SELECT version FROM agent_version WHERE agent_name = ? ORDER BY released_at DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), agent);
        return rows.isEmpty() ? "v0" : rows.getFirst();
    }

    private record Step(String name, String fullName, int target, Integer previous, String sha) {}

    private record Passed(int version, String sha) {}
}
