package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.litellm.LiteLlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;

@Service
public class LiteLlmProvisioner implements ProvisionStep {
    private final LiteLlmClient client;
    private final SecretWriter secrets;
    private final String rateText;

    public LiteLlmProvisioner(LiteLlmClient client, SecretWriter secrets,
                              @Value("${KEEL_USD_CNY_RATE:}") String rateText) {
        this.client = client;
        this.secrets = secrets;
        this.rateText = rateText == null ? "" : rateText;
    }

    @Override
    public String resourceType() {
        return "litellm_key";
    }

    @Override
    public void provision(String agent, String env, JsonNode manifest) {
        if (rateText.isBlank()) {
            throw new IllegalStateException("KEEL_USD_CNY_RATE 未配置");
        }
        var rate = new BigDecimal(rateText);
        if (rate.signum() <= 0) {
            throw new IllegalStateException("KEEL_USD_CNY_RATE 未配置");
        }
        var cnyNode = manifest.path("spec").path("models").path("budget").path("dailyCny");
        if (!cnyNode.isNumber()) {
            throw new IllegalStateException("dailyCny 缺失");
        }
        var usd = cnyNode.decimalValue().divide(rate, 8, RoundingMode.HALF_UP);
        var models = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        var defaultModel = manifest.path("spec").path("models").path("default").asText("");
        if (!defaultModel.isBlank()) {
            seen.add(defaultModel);
        }
        manifest.path("spec").path("models").path("fallback").forEach(node -> seen.add(node.asText()));
        models.addAll(seen);
        var alias = agent + "-" + env;
        var key = client.generate(alias, models, usd, agent);
        secrets.rememberLlmKey(agent, key);
    }

    @Override
    public void revoke(String agent, String env) {
        client.block(agent + "-" + env);
        secrets.forgetLlmKey(agent);
    }
}
