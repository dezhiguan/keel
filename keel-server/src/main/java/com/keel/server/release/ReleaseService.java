package com.keel.server.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.prompt.PromptCatalog;
import com.keel.server.prompt.PromptService;
import com.keel.server.prompt.PromptTexts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

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
    private final String ciClient;
    private final ObjectMapper json = new ObjectMapper();

    public ReleaseService(PromptCatalog catalog, LangfuseClient langfuse, JdbcTemplate jdbc, AuditStore audit,
                          PromptService prompts, @Value("${keel.ci.client-id:}") String ciClient) {
        this.catalog = catalog;
        this.langfuse = langfuse;
        this.jdbc = jdbc;
        this.audit = audit;
        this.prompts = prompts;
        this.ciClient = ciClient == null ? "" : ciClient;
    }

    public Map<String, Object> publishEvaluation(String agent, String runId, String env) {
        catalog.authorize(agent);
        if (!List.of("staging", "prod").contains(env)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "env 只能是 staging 或 prod");
        }
        if (devflowJobId(agent) != null) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, "研发员工请从生产任务的可信 CI 发布");
        }
        var rows = jdbc.query("SELECT image,state,result::text FROM eval_run WHERE run_id=? AND agent_name=?",
                (rs, n) -> Map.of("image", rs.getString(1), "state", rs.getString(2),
                        "result", rs.getString(3) == null ? "{}" : rs.getString(3)), runId, agent);
        if (rows.isEmpty()) throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "没有这个智能体的评测运行");
        var row = rows.getFirst();
        JsonNode result;
        try { result = json.readTree(row.get("result")); }
        catch (Exception error) { throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "评测结果无效"); }
        requirePublishable(row.get("state"), result, row.get("image"));
        var versions = result.path("promptVersions");
        for (var item : catalog.declared(agent)) {
            var measured = versions.path(item.name());
            var staging = labelVersion(item.fullName(), "staging");
            if (!measured.canConvertToInt() || staging == null || measured.asInt() != staging) {
                throw new KeelException(ErrorCode.PROMPT_NOT_GATED, "当前 staging 提示词与完整评测实际版本不一致，请重新评测");
            }
        }
        var gate = json.createObjectNode().put("agent", agent).put("gateRunId", runId).put("passed", true);
        gate.set("promptVersions", versions);
        prompts.recordGate(gate);
        // Snapshot the exact tested image before any production label is moved.
        var version = snapshotEvaluatedVersion(agent, runId, row.get("image"));
        var body = json.createObjectNode().put("env", env).put("gateRunId", runId)
                .put("evalRunId", runId).put("image", row.get("image"));
        var published = release(agent, body);
        published.put("version", version);
        audit.append(agent, env, "config.change", "mid", "allowed", "agent:" + agent, null,
                PromptCatalog.Actor.current().userId(), Map.of("version", version, "gateRunId", runId));
        return published;
    }

    static void requirePublishable(String state, JsonNode result, String image) {
        if (!"DONE".equals(state) || !result.path("passed").asBoolean() || !result.path("complete").asBoolean()
                || !result.path("promptVersions").isObject() || result.path("caseCount").asInt() < 1
                || image == null || image.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "只能发布完整通过且包含实际提示词证据和镜像的评测");
        }
    }

    public Map<String, Object> release(String agent, JsonNode body) {
        var env = body.path("env").asText("");
        var gateRunId = body.path("gateRunId").asText("");
        var image = body.path("image").asText("");
        if (!"prod".equals(env) && !"staging".equals(env)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "env 只能是 staging 或 prod");
        }
        if (gateRunId.isBlank() || image.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "gateRunId 和 image 不能为空");
        }
        var jobId = devflowJobId(agent);
        if (jobId != null) {
            var principal = ConsolePrincipal.current();
            if (ciClient.isBlank() || principal == null || !"SERVICE".equals(principal.mode())
                    || !ciClient.equals(principal.username())) {
                throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, "研发员工只能由可信 CI 执行发布");
            }
            if (!image.matches("[^\\s]+@sha256:[0-9a-f]{64}")) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "研发员工发布镜像必须使用不可变 sha256 摘要");
            }
        } else {
            catalog.authorize(agent);
        }
        var rejection = jobId == null ? null : devflowEvidenceRejection(jobId, env, gateRunId, image,
                approvedMerge(agent, jobId));
        if (rejection != null) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, rejection);
        }
        var evalRunId = body.path("evalRunId").asText("");
        if (!evalRunId.isBlank() && !evalRunId.equals(gateRunId)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "评测运行号必须与门禁运行号一致");
        }
        if (jobId == null && evalRunId.isBlank()) requireLegacyGate(agent, gateRunId);
        // Validate the bound evaluation before changing any production labels.
        var version = !evalRunId.isBlank()
                ? snapshotEvaluatedVersion(agent, evalRunId, image) : agentVersion(agent);
        ObjectNode promptVersions = json.createObjectNode();
        if ("prod".equals(env)) {
            prompts.invalidateListCache();
            promptVersions = moveProduction(agent, gateRunId, jobId != null);
        }
        jdbc.update("""
                INSERT INTO release_record (agent_name, version, env, gate_passed, prompt_versions_json, ci_run_url, image, kind)
                VALUES (?, ?, ?, true, ?::jsonb, ?, ?, 'release')
                """, agent, version, env, promptVersions.toString(), gateRunId, image);
        var result = new LinkedHashMap<String, Object>();
        result.put("version", version);
        result.put("env", env);
        result.put("gatePassed", true);
        result.put("image", image);
        result.put("ciRunUrl", gateRunId);
        result.put("releasedBy", PromptCatalog.Actor.current().userId());
        return result;
    }

    private void requireLegacyGate(String agent, String runId) {
        var declared = catalog.declared(agent);
        if (declared.isEmpty()) {
            throw new KeelException(ErrorCode.PROMPT_NOT_GATED, "没有可核对的提示词门禁，请使用完整的平台评测发布");
        }
        for (var item : declared) {
            var staging = labelVersion(item.fullName(), "staging");
            var passed = latestPassed(agent, item.name(), runId);
            if (staging == null || passed == null || staging != passed.version()) {
                throw new KeelException(ErrorCode.PROMPT_NOT_GATED, ErrorCode.PROMPT_NOT_GATED.message());
            }
        }
    }

    /** Preserve the tested source environment manifest as the next immutable version. */
    private String snapshotEvaluatedVersion(String agent, String evalRunId, String image) {
        var evaluated = jdbc.query("""
                SELECT image, state, COALESCE((result->>'passed')::boolean, false) AND COALESCE((result->>'complete')::boolean, false), env, created_at
                FROM eval_run WHERE run_id=? AND agent_name=?
                """, (rs, row) -> new Evaluated(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getTimestamp(5).toInstant()),
                evalRunId, agent);
        if (evaluated.isEmpty() || !"DONE".equals(evaluated.getFirst().state())
                || !evaluated.getFirst().passed() || !image.equals(evaluated.getFirst().image())) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "评测须通过，且运行镜像必须与发布镜像一致");
        }
        var sourceEnv = evaluated.getFirst().env();
        var prior = jdbc.query("""
                SELECT version FROM agent_version WHERE agent_name=? AND env=? AND gate_run_id=? LIMIT 1
                """, (rs, row) -> rs.getString(1), agent, sourceEnv, evalRunId);
        if (!prior.isEmpty()) return prior.getFirst();
        var candidateTimes = jdbc.query("""
                SELECT released_at FROM agent_version WHERE agent_name=? AND env=?
                ORDER BY released_at DESC,id DESC LIMIT 1
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), agent, sourceEnv);
        if (candidateTimes.isEmpty()) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "没有可发布的候选配置");
        requireCurrentCandidate(evaluated.getFirst().createdAt(), candidateTimes.getFirst());
        var latest = jdbc.query("""
                SELECT version FROM agent_version WHERE agent_name=? AND env=? AND version ~ '^v[0-9]+$'
                ORDER BY CAST(substring(version FROM 2) AS integer) DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), agent, sourceEnv);
        if (latest.isEmpty() || !latest.getFirst().matches("v[0-9]+")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "没有可发布的 " + sourceEnv + " 智能体版本");
        }
        var version = "v" + (Integer.parseInt(latest.getFirst().substring(1)) + 1);
        var inserted = jdbc.update("""
                INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash,
                                           image, gate_run_id, released_by)
                SELECT agent_name, ?, env, manifest_json, manifest_hash, ?, ?, ?
                FROM agent_version WHERE agent_name=? AND env=?
                ORDER BY released_at DESC, id DESC LIMIT 1
                """, version, image, evalRunId, PromptCatalog.Actor.current().userId(), agent, sourceEnv);
        if (inserted != 1) throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, "智能体版本快照未创建");
        return version;
    }

    static void requireCurrentCandidate(java.time.Instant evaluatedAt, java.time.Instant candidateAt) {
        if (evaluatedAt == null || candidateAt == null || evaluatedAt.isBefore(candidateAt)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "候选配置在评测后已更新，请重新评测再发布");
        }
    }

    private record Evaluated(String image, String state, boolean passed, String env, java.time.Instant createdAt) {}

    private String devflowEvidenceRejection(String jobId, String env, String gateRunId,
                                           String image, boolean mergeApproved) {
        var executions = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=?",
                (rs, row) -> rs.getString(1), jobId);
        JsonNode state = null;
        if (!executions.isEmpty()) {
            try { state = json.readTree(executions.getFirst()); }
            catch (Exception error) { return "元智能体生产任务的可信执行记录损坏"; }
        }
        var passed = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM devflow_holdout_result WHERE job_id=? AND passed)",
                Boolean.class, jobId);
        return ReleaseGuard.evidenceRejection(state, env, gateRunId, image, Boolean.TRUE.equals(passed), mergeApproved);
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

    private String devflowJobId(String agent) {
        var ids = jdbc.query("SELECT devflow_job_id FROM agent WHERE name = ?", (rs, row) -> rs.getString(1), agent);
        if (!ids.isEmpty() && ids.getFirst() != null && !ids.getFirst().isBlank()) {
            return ids.getFirst();
        }
        // Candidate registration does not yet populate agent.devflow_job_id. Resolve by the
        // immutable target name so a produced employee cannot bypass H4 by registering directly.
        var produced = jdbc.query("""
                SELECT job_id FROM devflow_job WHERE target_agent = ? AND layer = 'DEV' AND kind = 'CREATE'
                  AND status <> 'CANCEL' ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), agent);
        return produced.isEmpty() ? null : produced.getFirst();
    }

    private boolean approvedMerge(String agent, String jobId) {
        if (jobId == null || jobId.isBlank()) return false;
        var count = jdbc.queryForObject("""
                SELECT count(*) FROM approval_request
                WHERE agent_name = ? AND subject_type = 'tool.call' AND subject_ref = 'git.pr.merge'
                  AND devflow_job_id = ? AND devflow_gate = 'H4' AND status = 'APPROVED'
                """, Integer.class, agent, jobId);
        return count != null && count > 0;
    }

    private ObjectNode moveProduction(String agent, String gateRunId, boolean trustedCi) {
        if (!langfuse.ready()) {
            throw new KeelException(ErrorCode.PROMPT_UNAVAILABLE, "Langfuse 未配置");
        }
        var plan = new ArrayList<Step>();
        var declared = trustedCi ? catalog.declaredForDriftScan().stream()
                .filter(item -> agent.equals(item.agent())).toList() : catalog.declared(agent);
        for (var item : declared) {
            var staging = labelVersion(item.fullName(), "staging");
            var passed = latestPassed(agent, item.name(), gateRunId);
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

    private Passed latestPassed(String agent, String prompt, String runId) {
        var rows = jdbc.query("""
                SELECT version, sha256 FROM prompt_promotion
                WHERE agent_name = ? AND prompt_name = ? AND gate_status = 'PASSED' AND gate_run_id = ?
                ORDER BY promoted_at DESC, id DESC LIMIT 1
                """, (rs, n) -> new Passed(rs.getInt(1), rs.getString(2)), agent, prompt, runId);
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
