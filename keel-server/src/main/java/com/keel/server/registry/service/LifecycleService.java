package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.provisioning.ProvisioningService;
import com.keel.server.provisioning.ResourceLedger;
import com.keel.server.provisioning.SecretWriter;
import com.keel.server.registry.mapper.AgentMapper;
import com.keel.server.tool.service.ToolRegistryService;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.enums.AgentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LifecycleService {
    private final AgentMapper agents;
    private final ProvisioningService provisioning;
    private final ResourceLedger ledger;
    private final JdbcTemplate jdbc;
    private final ManifestValidator validator;
    private final ObjectMapper json;
    private final AuditStore audits;
    private final WizardCatalog wizardCatalog;
    private final ToolRegistryService toolRegistry;
    private final SecretWriter secrets;
    private final int port;

    public LifecycleService(AgentMapper agents, ProvisioningService provisioning, ResourceLedger ledger,
                            JdbcTemplate jdbc, ManifestValidator validator, ObjectMapper json, AuditStore audits,
                            WizardCatalog wizardCatalog, ToolRegistryService toolRegistry, SecretWriter secrets,
                            @Value("${server.port:8080}") int port) {
        this.agents = agents;
        this.provisioning = provisioning;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.validator = validator;
        this.json = json;
        this.audits = audits;
        this.wizardCatalog = wizardCatalog;
        this.toolRegistry = toolRegistry;
        this.secrets = secrets;
        this.port = port;
    }

    public SelfCheckService.Report register(JsonNode body) {
        var shared = body.has("apiVersion")
                ? wizardCatalog.sharedTools().stream().map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet())
                : Set.<String>of();
        var selection = selectionForm(json, body, shared);
        var selected = wizardCatalog.resolve(selection);
        var knowledge = wizardCatalog.resolveKnowledge(selection);
        var manifest = manifest(body, selected, knowledge);
        validator.validate(manifest, java.util.Set.of());
        var name = manifest.path("metadata").path("name").asText();
        var env = body.path("env").asText("dev");
        if (!env.equals("dev") && !env.equals("test") && !env.equals("staging") && !env.equals("prod")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        }
        var approvedGrants = approvedDevflowGrants(name, selected, manifest, env);
        var existing = agents.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Agent>()
                .eq(Agent::getName, name));
        rejectRemovedName(existing);
        if (existing != null && ledger.hasActive(name, env)) {
            throw new KeelException(ErrorCode.AGENT_NAME_TAKEN, ErrorCode.AGENT_NAME_TAKEN.message());
        }
        if (existing == null) {
            insertAgent(manifest);
        }
        if (approvedGrants != null) {
            jdbc.update("UPDATE agent SET devflow_job_id=?, layer='DEV' WHERE name=?",
                    approvedGrants.jobId(), name);
            for (var grant : approvedGrants.grants().values()) {
                toolRegistry.grant(grant.name(), name, grant.version(), grant.grantedBy());
            }
        } else if (!selected.isEmpty()) {
            var grantedBy = ConsolePrincipal.requireUsername();
            for (var tool : selected) {
                toolRegistry.grant(tool.name(), name, tool.version(), grantedBy);
            }
        }
        var text = manifest.toString();
        jdbc.update("""
                INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash, released_by)
                VALUES (?, 'v0', ?, ?::jsonb, ?, 'keel')
                ON CONFLICT (agent_name, env, version) DO UPDATE SET manifest_json = EXCLUDED.manifest_json,
                    manifest_hash = EXCLUDED.manifest_hash, released_at = now()
                """, name, env, text, sha256(text));
        var endpoint = manifest.path("spec").path("runtime").path("endpoint").asText();
        if (!knowledge.isEmpty()) {
            secrets.prepareKnowledgeKey(name, env, knowledge);
        }
        SelfCheckService.Report report;
        try {
            report = provisioning.register(name, env, endpoint, manifest);
        } catch (RuntimeException e) {
            secrets.discardKnowledgeKey(name);
            // Provisioning already compensates external resources. Keep a
            // retriable draft; a failed attempt must never look registered.
            jdbc.update("UPDATE agent SET status='DRAFT', updated_at=now() WHERE name=?", name);
            audits.append(name, env, "agent.register", "high", "denied", name, null);
            var reason = RegisterManifest.missingExternal(e.getMessage())
                    ? e.getMessage() : "外部资源开通失败（" + e.getClass().getSimpleName() + "），请检查服务日志后重试";
            return new SelfCheckService.Report(false, java.util.List.of(
                    new SelfCheckService.Item("开通", false, reason)));
        }
        jdbc.update("UPDATE agent SET status = ? WHERE name = ?", report.passed() ? "REGISTERED" : "DRAFT", name);
        if (report.passed()) {
            jdbc.update("INSERT INTO route_snapshot (changed_agent) VALUES (?)", name);
        }
        return report;
    }

    private ApprovedDevflow approvedDevflowGrants(String name, List<WizardTools.Spec> selected,
                                                 JsonNode manifest, String env) {
        var jobs = jdbc.query("""
                SELECT job_id, tools_json::text FROM devflow_job
                WHERE target_agent=? AND layer='DEV' AND kind='CREATE' AND status <> 'CANCEL'
                ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)), name);
        if (jobs.isEmpty()) {
            if (Set.of("dev-lead", "spec-agent", "dev-agent", "eval-agent", "code-review",
                    "ci-doctor", "release-agent").contains(name)) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "研发数字员工必须由 meta-agent 的生产任务登记");
            }
            return null;
        }
        var job = jobs.getFirst();
        var expected = new java.util.HashSet<String>();
        try {
            for (var tool : json.readTree(job.getValue())) expected.add(tool.path("name").asText());
        } catch (Exception error) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "生产任务工具清单无效");
        }
        var grants = jdbc.query("SELECT tool_name, version_range, granted_by FROM devflow_tool_grant WHERE job_id=?",
                (rs, n) -> new ApprovedGrant(rs.getString(1), rs.getString(2), rs.getString(3)), job.getKey());
        var approved = grants.stream().collect(Collectors.toMap(ApprovedGrant::name, value -> value));
        var declared = manifest.path("spec").path("tools");
        var requested = new java.util.HashSet<String>();
        for (var tool : declared) {
            var toolName = tool.path("name").asText("");
            if (toolName.isBlank() || !requested.add(toolName)) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "候选员工工具清单包含重复或空名称");
            }
        }
        if (!requested.equals(expected) || !approved.keySet().equals(expected)
                || (!selected.isEmpty() && selected.stream().anyMatch(tool ->
                        !tool.version().equals(approved.get(tool.name()).version())))) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "候选员工工具清单与 H3 人工授权不一致");
        }
        if (selected.isEmpty()) {
            for (var tool : declared) {
                var grant = approved.get(tool.path("name").asText());
                var versions = jdbc.query("""
                        SELECT lower(access), lower(risk), approval_policy_id FROM tool_version
                        WHERE tool_name=? AND version=?
                        """, (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3)},
                        grant.name(), grant.version());
                if (versions.size() != 1 || !versions.getFirst()[0].equals(tool.path("access").asText())
                        || !versions.getFirst()[1].equals(tool.path("risk").asText())
                        || ("high".equals(versions.getFirst()[1])
                            && (versions.getFirst()[2] == null || !"required".equals(tool.path("approval").asText())))) {
                    throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "候选员工 manifest 工具权限与注册表不一致");
                }
            }
        }
        requireProducedStage(job.getKey(), name, env);
        return new ApprovedDevflow(job.getKey(), approved);
    }

    private void requireProducedStage(String jobId, String name, String env) {
        if (!"staging".equals(env) && !"prod".equals(env)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "研发员工只能在通过 S4 后登记 staging，再经 H4 登记 prod");
        }
        var rows = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=?",
                (rs, row) -> rs.getString(1), jobId);
        JsonNode state;
        try { state = rows.isEmpty() ? null : json.readTree(rows.getFirst()); }
        catch (Exception error) { state = null; }
        if (state == null || !"PASS".equals(state.path("sandboxStatus").asText())
                || !state.path("humanReview").path("approved").asBoolean(false)
                || !state.path("modelReview").path("approved").asBoolean(false)
                || !Set.of("STAGING_READY", "STAGING_GATE_PASSED", "RELEASING", "WATCHING")
                       .contains(state.path("phase").asText())) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "候选员工尚未通过代码评审与人工 PR review");
        }
        if ("prod".equals(env)) {
            var gate = state.path("gate");
            var prodGate = state.path("prodGate");
            var accepted = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM approval_request
                        WHERE agent_name=? AND devflow_job_id=? AND devflow_gate='H4'
                          AND subject_type='tool.call' AND subject_ref='git.pr.merge' AND status='APPROVED')
                    """, Boolean.class, name, jobId);
            var hidden = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM devflow_holdout_result WHERE job_id=? AND passed)",
                    Boolean.class, jobId);
            if (!"RELEASING".equals(state.path("phase").asText()) || !gate.path("passed").asBoolean(false)
                    || !state.path("delivery").path("sha").asText().equals(gate.path("sha").asText())
                    || !prodGate.path("passed").asBoolean(false)
                    || !"prod".equals(prodGate.path("env").asText())
                    || !state.path("mergeSha").asText().equals(prodGate.path("sha").asText())
                    || !Boolean.TRUE.equals(accepted) || !Boolean.TRUE.equals(hidden)) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "生产登记需要 staging、合并提交的 prod 门禁、隐藏考题和 H4 审批");
            }
        }
    }

    private record ApprovedDevflow(String jobId, Map<String, ApprovedGrant> grants) {}
    private record ApprovedGrant(String name, String version, String grantedBy) {}

    /** Revoke the environment's external resources and mark the agent RETIRED. History rows stay. */
    public void retire(String name, String env) {
        if (!env.equals("dev") && !env.equals("test") && !env.equals("staging") && !env.equals("prod")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        }
        var existing = agents.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Agent>()
                .eq(Agent::getName, name).eq(Agent::getKind, Agent.KIND_AGENT));
        if (existing == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        if (existing.getStatus() == AgentStatus.RETIRED) {
            return;
        }
        audits.append(name, env, "agent.retire", "high", "allowed", name, null);
        provisioning.retire(name, env);
        existing.setStatus(AgentStatus.RETIRED);
        agents.updateById(existing);
        jdbc.update("INSERT INTO route_snapshot (changed_agent) VALUES (?)", name);
    }

    static void rejectRemovedName(Agent existing) {
        if (existing != null && existing.isRemoved()) {
            throw new KeelException(ErrorCode.AGENT_NAME_TAKEN, ErrorCode.AGENT_NAME_TAKEN.message());
        }
    }

    /** Hide a retired agent from the registry. The row, audit events and traces stay. */
    public void delete(String name) {
        var existing = agents.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Agent>()
                .eq(Agent::getName, name).eq(Agent::getKind, Agent.KIND_AGENT));
        agents.updateById(markRemoved(existing));
    }

    static Agent markRemoved(Agent existing) {
        if (existing == null || existing.isRemoved()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        if (existing.getStatus() != AgentStatus.RETIRED) {
            throw new KeelException(ErrorCode.AGENT_NOT_RETIRED, ErrorCode.AGENT_NOT_RETIRED.message());
        }
        existing.setRemoved(true);
        return existing;
    }

    private void insertAgent(JsonNode manifest) {
        var runtime = manifest.path("spec").path("runtime");
        var agent = new Agent();
        agent.setName(manifest.path("metadata").path("name").asText());
        agent.setDisplayName(manifest.path("metadata").path("displayName").asText(agent.getName()));
        agent.setKind(Agent.KIND_AGENT);
        agent.setRuntime(runtime.path("type").asText("code"));
        agent.setLanguage(runtime.path("language").asText("python"));
        var owner = manifest.path("metadata").path("owner").asText(" / ");
        var parts = owner.split(" / ", 2);
        agent.setOwnerOrg(parts[0].isBlank() ? "unknown" : parts[0]);
        agent.setOwnerUser(parts.length > 1 && !parts[1].isBlank() ? parts[1] : "unknown");
        agent.setStatus(AgentStatus.DRAFT);
        agent.setLiveness(runtime.path("liveness").asText("k8s"));
        agents.insert(agent);
    }

    private JsonNode manifest(JsonNode body, java.util.List<WizardTools.Spec> tools, java.util.List<String> knowledge) {
        return RegisterManifest.toNode(json, body, port, tools, knowledge);
    }

    /** Raw manifests still select catalog resources; bypassing the wizard must not drop their grants or RAG key. */
    static JsonNode selectionForm(ObjectMapper json, JsonNode body, Set<String> sharedTools) {
        if (!body.has("apiVersion")) return body;
        var form = json.createObjectNode();
        var tools = form.putArray("tools");
        body.path("spec").path("tools").forEach(item -> {
            var name = item.path("name").asText("");
            if (sharedTools.contains(name)) tools.add(name);
        });
        var knowledge = form.putArray("knowledgeBases");
        body.path("spec").path("knowledge").forEach(item -> knowledge.add(item.path("kb").asText("")));
        return form;
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
