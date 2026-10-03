package com.keel.server.integration.authgw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class AuthGatewayClient {
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public AuthGatewayClient(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
    }

    public void register(Map<String, Object> body) {
        send("POST", "/internal/clients", body);
    }

    public void delete(String clientId) {
        send("DELETE", "/internal/clients/" + clientId, null);
    }

    public int exchange(String assertion) {
        return send("POST", "/oauth/token-exchange", Map.of(
                "grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
                "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion", assertion,
                "requested_audience", "keel-api",
                "requested_scopes", "agent:invoke"));
    }

    private int send(String method, String path, Object body) {
        if (baseUrl.isBlank()) {
            throw new KeelException(ErrorCode.AUDIT_WRITE_FAILED, "KEEL_AUTH_GATEWAY_URL 未配置");
        }
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(3));
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else if (path.endsWith("/oauth/token-exchange")) {
                @SuppressWarnings("unchecked")
                var form = (Map<String, String>) body;
                var encoded = form.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                        .reduce((a, b) -> a + "&" + b).orElse("");
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                        .method(method, HttpRequest.BodyPublishers.ofString(encoded));
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("auth-gateway " + response.statusCode());
            }
            return response.statusCode();
        } catch (KeelException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
