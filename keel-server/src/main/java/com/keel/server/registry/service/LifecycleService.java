package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.provisioning.ProvisioningService;
import com.keel.server.provisioning.ResourceLedger;
import com.keel.server.registry.mapper.AgentMapper;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.enums.AgentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class LifecycleService {
    private final AgentMapper agents;
    private final ProvisioningService provisioning;
    private final ResourceLedger ledger;
    private final JdbcTemplate jdbc;
    private final ManifestValidator validator;
    private final ObjectMapper json;
    private final AuditStore audits;
    private final int port;

    public LifecycleService(AgentMapper agents, ProvisioningService provisioning, ResourceLedger ledger,
                            JdbcTemplate jdbc, ManifestValidator validator, ObjectMapper json, AuditStore audits,
                            @Value("${server.port:8080}") int port) {
        this.agents = agents;
        this.provisioning = provisioning;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.validator = validator;
        this.json = json;
        this.audits = audits;
        this.port = port;
    }

    public SelfCheckService.Report register(JsonNode body) {
        var manifest = manifest(body);
        validator.validate(manifest, java.util.Set.of());
        var name = manifest.path("metadata").path("name").asText();
        var env = body.path("env").asText("dev");
        if (!env.equals("dev") && !env.equals("test") && !env.equals("staging") && !env.equals("prod")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        }
        var existing = agents.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Agent>()
                .eq(Agent::getName, name));
        if (existing != null && ledger.hasActive(name, env)) {
            throw new KeelException(ErrorCode.AGENT_NAME_TAKEN, ErrorCode.AGENT_NAME_TAKEN.message());
        }
        if (existing == null) {
            insertAgent(manifest);
        }
        var text = manifest.toString();
        jdbc.update("""
                INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash, released_by)
                VALUES (?, 'v0', ?, ?::jsonb, ?, 'keel')
                ON CONFLICT (agent_name, env, version) DO UPDATE SET manifest_json = EXCLUDED.manifest_json,
                    manifest_hash = EXCLUDED.manifest_hash, released_at = now()
                """, name, env, text, sha256(text));
        var endpoint = manifest.path("spec").path("runtime").path("endpoint").asText();
        SelfCheckService.Report report;
        try {
            report = provisioning.register(name, env, endpoint, manifest);
        } catch (RuntimeException e) {
            if (!RegisterManifest.missingExternal(e.getMessage())) {
                throw e;
            }
            audits.append(name, env, "agent.register", "high", "allowed", name, null);
            report = new SelfCheckService.Report(false, java.util.List.of(
                    new SelfCheckService.Item("开通", false, e.getMessage())));
        }
        if (report.passed()) {
            jdbc.update("INSERT INTO route_snapshot (changed_agent) VALUES (?)", name);
        }
        return report;
    }

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
        agent.setStatus(AgentStatus.REGISTERED);
        agent.setLiveness(runtime.path("liveness").asText("k8s"));
        agents.insert(agent);
    }

    private JsonNode manifest(JsonNode body) {
        return RegisterManifest.toNode(json, body, port);
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
