package com.keel.audit.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;

/** Deployment-managed snapshots of effective manifest whitelists; never supplied by a caller. */
@Component
public class AuditCapturePolicy {
    private record Grant(Set<String> fields, byte[] tokenHash) {}
    private final Map<String, Grant> grants = new HashMap<>();

    public AuditCapturePolicy(@Value("${KEEL_AUDIT_CAPTURE_POLICIES:#{null}}") String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            JsonNode root = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(raw);
            if (!root.isObject()) throw new IllegalArgumentException();
            var agents = root.fields();
            while (agents.hasNext()) {
                var agent = agents.next();
                if (!agent.getKey().matches("[a-z][a-z0-9-]{1,62}") || !agent.getValue().isObject()) throw new IllegalArgumentException();
                var envs = agent.getValue().fields();
                while (envs.hasNext()) {
                    var env = envs.next(); var value = env.getValue();
                    if (!Set.of("staging", "prod").contains(env.getKey()) || !value.isObject()
                            || value.size() != 2 || !value.path("captureFields").isArray()
                            || !value.path("tokenSha256").asText().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                    var fields = new HashSet<String>();
                    for (var field : value.path("captureFields")) {
                        if (!field.isTextual() || !field.asText().matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}") || !fields.add(field.asText())) throw new IllegalArgumentException();
                    }
                    grants.put(agent.getKey() + ":" + env.getKey(), new Grant(Set.copyOf(fields), HexFormat.of().parseHex(value.path("tokenSha256").asText())));
                }
            }
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid audit capture policy configuration");
        }
    }

    public Set<String> fields(JsonNode event, String authorization) {
        var grant = grants.get(event.path("agent").asText() + ":" + event.path("env").asText());
        if (grant == null) return Set.of();
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new SecurityException();
        try {
            var actual = MessageDigest.getInstance("SHA-256").digest(authorization.substring(7).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(actual, grant.tokenHash())) throw new SecurityException();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return grant.fields();
    }
}
