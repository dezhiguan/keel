package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.authgw.ClientAssertion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issues a read-only rag-forge API key for the knowledge bases selected at registration.
 * The caller is the provisioner identity in the keel-server Secret, not the console login
 * and not the metrics account. That identity is never copied into the agent Secret.
 */
@Component
public class RagForgeAccess {
    private static final String TARGET_AUDIENCE = "ragforge-admin-api";
    private final String baseUrl;
    private final String provisionToken;
    private final String account;
    private final String password;
    private final String clientId;
    private final String clientPrivateKey;
    private final String clientKid;
    private final String assertionAudience;
    private final String authGatewayUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private String cachedToken = "";
    private Instant cachedUntil = Instant.EPOCH;

    public RagForgeAccess(String baseUrl, String provisionToken) {
        this(baseUrl, provisionToken, "", "", "", "", "", "", "");
    }

    @Autowired
    public RagForgeAccess(@Value("${RAGFORGE_BASE_URL:}") String baseUrl,
                          @Value("${RAGFORGE_PROVISION_TOKEN:}") String provisionToken,
                          @Value("${RAGFORGE_PROVISION_ACCOUNT:}") String account,
                          @Value("${RAGFORGE_PROVISION_PASSWORD:}") String password,
                          @Value("${RAGFORGE_PROVISION_CLIENT_ID:}") String clientId,
                          @Value("${RAGFORGE_PROVISION_CLIENT_PRIVATE_KEY:}") String clientPrivateKey,
                          @Value("${RAGFORGE_PROVISION_CLIENT_KID:}") String clientKid,
                          @Value("${RAGFORGE_PROVISION_ASSERTION_AUDIENCE:}") String assertionAudience,
                          @Value("${KEEL_AUTH_GATEWAY_URL:}") String authGatewayUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.provisionToken = provisionToken == null ? "" : provisionToken.trim();
        this.account = account == null ? "" : account.trim();
        this.password = password == null ? "" : password;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientPrivateKey = clientPrivateKey == null ? "" : clientPrivateKey;
        this.clientKid = clientKid == null ? "" : clientKid.trim();
        this.assertionAudience = assertionAudience == null ? "" : assertionAudience.trim();
        this.authGatewayUrl = authGatewayUrl == null ? "" : authGatewayUrl.replaceAll("/$", "");
    }

    public record Issued(long id, String token, String url) {}

    public Issued issue(String agent, String env, List<String> names) {
        if (baseUrl.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "rag-forge 地址没有配置，不能发放知识库密钥");
        }
        var bearer = bearer();
        if (bearer.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "登录态里没有可以发给 rag-forge 的凭证");
        }
        var ids = ids(bearer, names);
        var body = json.createObjectNode();
        body.put("keyName", agent + "-" + env);
        body.put("scopeMode", "KB_LIST");
        body.put("accessLevel", "READ");
        var allowed = body.putArray("allowedKbIds");
        ids.forEach(allowed::add);
        var created = send(bearer, "POST", "/api/v1/keys", body);
        var data = created.path("data");
        var token = data.path("apiKey").asText("");
        var id = data.path("id").asLong(0);
        if (token.isBlank() || id <= 0) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "rag-forge 没有返回知识库密钥");
        }
        return new Issued(id, token, baseUrl);
    }

    /** 404 means the key is already gone. */
    public void revoke(long id) {
        if (baseUrl.isBlank() || id <= 0) {
            return;
        }
        var bearer = bearer();
        if (bearer.isBlank()) {
            return;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys/" + id))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + bearer)
                    .DELETE()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() < 300) {
                return;
            }
            throw new IllegalStateException("rag-forge 吊销密钥 " + response.statusCode());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("rag-forge 吊销密钥失败", e);
        }
    }

    private List<Long> ids(String bearer, List<String> names) {
        var listed = send(bearer, "GET", "/api/v1/kb", null);
        var rows = listed.path("data");
        if (rows.isObject()) {
            rows = rows.path("records").isArray() ? rows.path("records") : rows.path("list");
        }
        var ids = new ArrayList<Long>();
        var missing = new ArrayList<String>();
        for (var name : names) {
            long id = 0;
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (name.equals(row.path("name").asText(""))) {
                        id = row.path("id").asLong(0);
                        break;
                    }
                }
            }
            if (id <= 0) {
                missing.add(name);
            } else {
                ids.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "代发身份看不到知识库：" + String.join("、", missing));
        }
        return ids;
    }

    private JsonNode send(String bearer, String method, String path, ObjectNode body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + bearer);
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, refusal(response.statusCode(), response.body()));
            }
            if (response.body() == null || response.body().isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(response.body());
        } catch (KeelException e) {
            throw e;
        } catch (Exception e) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "rag-forge 暂时不可达");
        }
    }

    private static String refusal(int status, String body) {
        var message = "rag-forge 拒绝发放知识库密钥（" + status + "）";
        if (body == null || body.isBlank()) {
            return message;
        }
        try {
            var node = new ObjectMapper().readTree(body);
            var text = node.path("msg").asText(node.path("message").asText(""));
            if (!text.isBlank()) {
                return message + "：" + text;
            }
        } catch (Exception ignored) {
            return message;
        }
        return message;
    }

    private String bearer() {
        if (!provisionToken.isBlank()) {
            return provisionToken;
        }
        if (account.isBlank() || password.isBlank() || clientId.isBlank() || clientPrivateKey.isBlank()
                || clientKid.isBlank() || assertionAudience.isBlank() || authGatewayUrl.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "知识库代发身份没有配置");
        }
        if (!cachedToken.isBlank() && Instant.now().isBefore(cachedUntil)) {
            return cachedToken;
        }
        synchronized (this) {
            if (!cachedToken.isBlank() && Instant.now().isBefore(cachedUntil)) {
                return cachedToken;
            }
            cachedToken = login();
            return cachedToken;
        }
    }

    private String login() {
        var assertion = ClientAssertion.sign(clientPrivateKey, clientKid, clientId, assertionAudience);
        var form = new LinkedHashMap<String, String>();
        form.put("account", account);
        form.put("password", password);
        form.put("target_aud", TARGET_AUDIENCE);
        form.put("remember", "false");
        form.put("client_id", clientId);
        form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.put("client_assertion", assertion);
        try {
            var request = HttpRequest.newBuilder(URI.create(authGatewayUrl + "/auth/login/password"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "代发身份登录 rag-forge 失败（" + response.statusCode() + "）");
            }
            var token = json.readTree(response.body()).path("access_token").asText("");
            if (token.isBlank()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "代发身份登录没有返回访问票");
            }
            var expires = json.readTree(response.body()).path("expires_in").asLong(900);
            cachedUntil = Instant.now().plusSeconds(Math.max(60, expires - 60));
            return token;
        } catch (KeelException e) {
            throw e;
        } catch (Exception e) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "代发身份登录 rag-forge 失败");
        }
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }
}
