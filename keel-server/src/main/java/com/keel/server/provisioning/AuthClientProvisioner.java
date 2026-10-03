package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.authgw.AuthGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;

@Service
public class AuthClientProvisioner implements ProvisionStep {
    private final AgentKeys keys;
    private final SecretWriter secrets;
    private final AuthGatewayClient auth;
    private final String jwksBase;

    public AuthClientProvisioner(AgentKeys keys, SecretWriter secrets, AuthGatewayClient auth,
                                 @Value("${KEEL_JWKS_BASE:}") String jwksBase) {
        this.keys = keys;
        this.secrets = secrets;
        this.auth = auth;
        this.jwksBase = jwksBase == null ? "" : jwksBase;
    }

    @Override
    public String resourceType() {
        return "oauth_client";
    }

    @Override
    public void provision(String agent, String env, JsonNode manifest) {
        if (jwksBase.isBlank()) {
            throw new IllegalStateException("KEEL_JWKS_BASE 未配置");
        }
        var material = keys.generate(agent);
        secrets.rememberPrivateKey(agent, material.privatePem());
        var audiences = new LinkedHashSet<String>();
        audiences.add("keel-api");
        var audience = manifest.path("spec").path("auth").path("audience").asText(agent);
        audiences.add(audience);
        manifest.path("spec").path("delegates").forEach(node -> audiences.add(node.asText()));
        auth.register(Map.of(
                "client_id", agent,
                "jwks_uri", jwksBase + "/api/v1/agents/" + agent + "/jwks.json",
                "allowed_audiences", new ArrayList<>(audiences),
                "scopes", java.util.List.of("agent:invoke"),
                "grant_types", java.util.List.of("token-exchange")));
    }

    @Override
    public void revoke(String agent, String env) {
        auth.delete(agent);
        keys.remove(agent);
        secrets.revoke(agent, env);
    }
}
