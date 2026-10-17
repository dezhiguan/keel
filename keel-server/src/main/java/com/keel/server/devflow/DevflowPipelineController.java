package com.keel.server.devflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.prompt.PromptService;
import com.keel.server.registry.service.LifecycleService;
import com.keel.server.registry.service.SelfCheckService;
import com.keel.server.release.ReleaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Trusted CI operates only on the immutable snapshot selected by the production ledger. */
@RestController
@RequestMapping("/api/v1/devflow")
public class DevflowPipelineController {
    private final MetaAgentExecution execution;
    private final LifecycleService lifecycle;
    private final SelfCheckService checks;
    private final PromptService prompts;
    private final ReleaseService releases;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String ciClient;
    private final AuditStore audits;

    public DevflowPipelineController(MetaAgentExecution execution, LifecycleService lifecycle, SelfCheckService checks,
            PromptService prompts, ReleaseService releases, JdbcTemplate jdbc, ObjectMapper json, AuditStore audits,
            @Value("${keel.ci.client-id:}") String ciClient) {
        this.execution = execution; this.lifecycle = lifecycle; this.checks = checks; this.prompts = prompts;
        this.releases = releases; this.jdbc = jdbc; this.json = json; this.ciClient = ciClient; this.audits = audits;
    }

    @PostMapping("/jobs/{jobId}/pipeline/run")
    public R<ObjectNode> run(@PathVariable String jobId, @RequestBody JsonNode body) {
        MetaAgentExecution.requireAdmin();
        var state = execution.get(jobId);
        MetaAgentExecution.validatePipelineState(state, body.path("env").asText());
        audits.append(state.path("requirement").path("target_agent").asText(), body.path("env").asText(),
                "release.pipeline", "high", "allowed", jobId, null);
        return R.ok(execution.runPipeline(jobId, body.path("env").asText()));
    }

    @PostMapping("/ci/jobs/{jobId}/{action}")
    public R<?> step(@PathVariable String jobId, @PathVariable String action, @RequestBody JsonNode body) throws Exception {
        requireCi();
        String env = body.path("env").asText();
        if ("status".equals(action)) return R.ok(execution.recordPipelineStatus(jobId, env,
                body.path("sha").asText(), body.path("runUrl").asText(), body.path("status").asText(),
                body.path("message").asText(), body.path("evaluationFailed").asBoolean(false)));
        var context = execution.pipelineContext(jobId, env, "context".equals(action) || "prepare".equals(action));
        if (!"context".equals(action) && !context.path("sha").asText().equals(body.path("sha").asText()))
            throw invalid("流水线步骤与当前提交不一致");
        var manifest = (ObjectNode) context.path("manifest");
        String name = context.path("agent").asText();
        if ("context".equals(action)) return R.ok(context);
        if ("prepare".equals(action)) {
            // prod provisioning follows the independent gate on the H4 merge commit.
            if ("staging".equals(env) || context.path("prodGate").path("passed").asBoolean(false)) {
                var registered = jdbc.query("SELECT manifest_json::text FROM agent_version WHERE agent_name=? AND env=? ORDER BY released_at DESC LIMIT 1",
                        (rs, n) -> rs.getString(1), name, env);
                boolean active = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM agent_resource WHERE agent_name=? AND env=? AND status='ACTIVE')",
                        Boolean.class, name, env));
                if (!active) {
                    var request = manifest.deepCopy().put("env", env);
                    lifecycle.register(request);
                } else {
                    if (registered.isEmpty()) throw invalid("已有运行资源缺少登记配置");
                    var existing = (ObjectNode) json.readTree(registered.getFirst());
                    existing.remove("env");
                    if (!existing.equals(manifest)) throw invalid("已有运行资源的 manifest 与本次可信候选不一致");
                }
                var sync = json.createObjectNode().put("gitSha", context.path("sha").asText());
                var files = sync.putArray("files");
                for (var declared : manifest.path("spec").path("prompts").path("items")) {
                    String promptName = declared.path("name").asText(), type = declared.path("type").asText();
                    String path = "prompts/" + promptName + ("chat".equals(type) ? ".json" : ".md");
                    JsonNode source = null;
                    for (var file : context.path("snapshot").path("files"))
                        if (path.equals(file.path("path").asText())) source = file.get("content");
                    if (source == null) throw invalid("精确提交缺少声明的提示词");
                    JsonNode prompt = "chat".equals(type) ? json.readTree(source.asText()) : source;
                    String canonical = "chat".equals(type) ? json.writeValueAsString(prompt) : prompt.asText();
                    var entry = files.addObject().put("name", promptName).put("type", type)
                            .put("sha256", "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8))));
                    entry.set("prompt", prompt);
                }
                if ("staging".equals(env)) prompts.syncCandidate(name, sync);
                else prompts.sync(name, sync);
            }
            return R.ok(Map.of("prepared", true));
        }
        if ("ready".equals(action)) {
            String endpoint = manifest.path("spec").path("runtime").path("endpoint").asText();
            boolean preview = "prod".equals(env) && !context.path("prodGate").path("passed").asBoolean(false);
            var report = preview ? checks.checkRuntime(name, env, endpoint, manifest)
                    : checks.checkRegistered(name, env, endpoint, manifest.deepCopy().put("env", env));
            if (report.passed() && !preview) {
                jdbc.update("UPDATE agent SET status='REGISTERED', updated_at=now() WHERE name=?", name);
                jdbc.update("INSERT INTO route_snapshot (changed_agent) VALUES (?)", name);
            }
            return R.ok(report);
        }
        if ("complete".equals(action)) {
            var gate = execution.get(jobId).path("prod".equals(env) ? "prodGate" : "gate");
            if (!gate.path("passed").asBoolean(false) || !body.path("gateRunId").asText().equals(gate.path("runId").asText())
                    || !body.path("image").asText().equals(gate.path("image").asText())) throw invalid("缺少本次可信门禁证据");
            prompts.recordGate(json.createObjectNode().put("agent", name).put("gateRunId", gate.path("runId").asText()).put("passed", true));
            return R.ok(releases.release(name, body));
        }
        throw invalid("未知流水线步骤");
    }

    private void requireCi() {
        var principal = ConsolePrincipal.current();
        if (ciClient.isBlank() || principal == null || !"SERVICE".equals(principal.mode())
                || !ciClient.equals(principal.username()))
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
    }

    private static KeelException invalid(String text) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, text);
    }
}
