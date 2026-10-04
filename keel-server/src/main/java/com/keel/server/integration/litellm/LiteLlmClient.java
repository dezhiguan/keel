package com.keel.server.integration.litellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Thin-gateway admin API. Budget is CNY and is not converted. */
public class LiteLlmClient {
    private final String baseUrl;
    private final String masterKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public LiteLlmClient(String baseUrl, String masterKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    public String generate(String alias, List<String> models, List<String> fallback, BigDecimal dailyBudgetCny, boolean allowFallback) {
        var body = Map.of(
                "alias", alias,
                "models", models,
                "fallback", fallback,
                "dailyBudgetCny", dailyBudgetCny,
                "allowFallback", allowFallback);
        var response = send("/admin/v1/keys", body);
        var key = response.path("key").asText("");
        if (key.isBlank()) {
            throw new IllegalStateException("薄网关没有返回虚拟 Key");
        }
        return key;
    }

    /** False only when the gateway answers 404 for this alias. Other failures propagate. */
    public boolean hasAlias(String alias) {
        try {
            get("/admin/v1/keys/" + alias);
            return true;
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            if (message.endsWith(" 404")) {
                return false;
            }
            throw e;
        }
    }

    public void block(String alias) {
        send("/admin/v1/keys/" + alias + "/block", Map.of());
    }

    /** True when spend has a row at or after {@code since}. Null when the gateway cannot be read. */
    public Boolean calledSince(String alias, Instant since) {
        if (baseUrl.isBlank() || masterKey.isBlank()) {
            return null;
        }
        try {
            for (int page = 1; page <= 20; page++) {
                var body = get("/admin/v1/spend?alias=" + alias + "&page=" + page + "&size=50");
                var data = body.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    return false;
                }
                var recent = false;
                for (var row : data) {
                    var ts = row.path("ts").asText("");
                    if (!ts.isBlank() && !Instant.parse(ts).isBefore(since)) {
                        recent = true;
                    }
                }
                if (recent) {
                    return true;
                }
                if (data.size() < 50) {
                    return false;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public List<Spend> spendAll() {
        var rows = new ArrayList<Spend>();
        for (int page = 1; page <= 50; page++) {
            var data = get("/admin/v1/spend?page=" + page + "&size=50").path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            data.forEach(row -> rows.add(new Spend(row.path("alias").asText(""), row.path("model").asText(""), row.path("costCny").asDouble())));
            if (data.size() < 50) {
                break;
            }
        }
        return rows;
    }

    public List<Model> models() {
        var rows = new ArrayList<Model>();
        get("/admin/v1/models").path("data").forEach(row -> rows.add(new Model(
                row.path("name").asText(""),
                row.path("priceConfigured").asBoolean(false))));
        return rows;
    }

    public record Spend(String alias, String model, double costCny) {}

    public record Model(String name, boolean priceConfigured) {}

    private JsonNode get(String path) {
        if (baseUrl.isBlank() || masterKey.isBlank()) {
            throw new IllegalStateException("薄网关地址或管理密钥未配置");
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + masterKey)
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("薄网关 " + path + " " + response.statusCode());
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

    private JsonNode send(String path, Object body) {
        if (baseUrl.isBlank() || masterKey.isBlank()) {
            throw new IllegalStateException("薄网关地址或管理密钥未配置");
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
                throw new IllegalStateException("薄网关 " + path + " " + response.statusCode());
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
