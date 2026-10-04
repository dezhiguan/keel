package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.litellm.LiteLlmClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;

@Service
public class LiteLlmProvisioner implements ProvisionStep {
    private final LiteLlmClient client;
    private final SecretWriter secrets;

    public LiteLlmProvisioner(LiteLlmClient client, SecretWriter secrets) {
        this.client = client;
        this.secrets = secrets;
    }

    @Override
    public String resourceType() {
        return "litellm_key";
    }

    @Override
    public void provision(String agent, String env, JsonNode manifest) {
        var cnyNode = manifest.path("spec").path("models").path("budget").path("dailyCny");
        if (!cnyNode.isNumber()) {
            throw new IllegalStateException("dailyCny 缺失");
        }
        var models = new LinkedHashSet<String>();
        var fallback = new LinkedHashSet<String>();
        collect(manifest.path("spec").path("models"), models, fallback, false);
        manifest.path("spec").path("models").path("byPurpose").fields().forEachRemaining(entry ->
                collect(entry.getValue(), models, fallback, "embedding".equals(entry.getKey())));
        if (manifest.path("spec").path("models").path("byPurpose").path("embedding").path("fallback").size() > 0) {
            throw new IllegalStateException("向量化模型不允许 fallback");
        }
        var alias = agent + "-" + env;
        var key = client.generate(alias, new ArrayList<>(models), new ArrayList<>(fallback), cnyNode.decimalValue(), !fallback.isEmpty());
        secrets.rememberLlmKey(agent, key);
    }

    private static void collect(JsonNode node, LinkedHashSet<String> models, LinkedHashSet<String> fallback, boolean embedding) {
        var primary = node.path("default").asText("");
        if (!primary.isBlank()) {
            models.add(primary);
        }
        node.path("fallback").forEach(item -> {
            if (!item.asText("").isBlank()) {
                models.add(item.asText());
                if (!embedding) {
                    fallback.add(item.asText());
                }
            }
        });
    }

    @Override
    public void revoke(String agent, String env) {
        client.block(agent + "-" + env);
        secrets.forgetLlmKey(agent);
    }
}
