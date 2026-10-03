package com.keel.server.integration.litellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** LiteLLM admin API. Never calls /spend/logs or /key/delete. */
public class LiteLlmClient {
    private final String baseUrl;
    private final String masterKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public LiteLlmClient(String baseUrl, String masterKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    public String generate(String alias, List<String> models, BigDecimal maxBudgetUsd, String agent) {
        var body = Map.of(
                "models", models,
                "max_budget", maxBudgetUsd,
                "budget_duration", "1d",
                "key_alias", alias,
                "metadata", Map.of("agent", agent));
        var response = send("/key/generate", body);
        var key = response.path("key").asText("");
        if (key.isBlank()) {
            throw new IllegalStateException("LiteLLM 没有返回虚拟 Key");
        }
        return key;
    }

    public void block(String alias) {
        send("/key/block", Map.of("key_alias", alias));
    }

    private JsonNode send(String path, Object body) {
        if (baseUrl.isBlank() || masterKey.isBlank()) {
            throw new IllegalStateException("LiteLLM 地址或 master key 未配置");
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + masterKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("LiteLLM " + path + " " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
