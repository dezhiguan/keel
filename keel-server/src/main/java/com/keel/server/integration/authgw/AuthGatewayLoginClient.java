package com.keel.server.integration.authgw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class AuthGatewayLoginClient {
    private static final Logger log = LoggerFactory.getLogger(AuthGatewayLoginClient.class);
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public AuthGatewayLoginClient(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
    }

    public Issued password(String account, String password, String captcha, String challengeId,
                           String clientId, String assertion) {
        var form = new LinkedHashMap<String, String>();
        form.put("account", account);
        form.put("password", password);
        if (captcha != null && !captcha.isBlank()) form.put("captcha", captcha);
        if (challengeId != null && !challengeId.isBlank()) form.put("challenge_id", challengeId);
        form.put("target_aud", "keel-console");
        form.put("remember", "false");
        form.putAll(client(clientId, assertion));
        return issued(postForm("/auth/login/password", form));
    }

    public Issued sms(String phone, String code, String clientId, String assertion) {
        var form = new LinkedHashMap<String, String>();
        form.put("phone", phone);
        form.put("code", code);
        form.put("target_aud", "keel-console");
        form.put("remember", "false");
        form.putAll(client(clientId, assertion));
        return issued(postForm("/auth/login/mobile", form));
    }

    public Issued refresh(String refreshToken, String clientId, String assertion) {
        var form = new LinkedHashMap<String, String>();
        form.put("refresh_token", refreshToken);
        form.putAll(client(clientId, assertion));
        return issued(postForm("/auth/token/refresh", form));
    }

    public void logout(String accessToken) {
        if (baseUrl.isBlank() || accessToken == null || accessToken.isBlank()) return;
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/logout"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // 登出以清掉浏览器 Cookie 为准，网关暂时不可达也让用户离开登录态。
        }
    }

    /** 手机号未开通时网关返回 409。调用方仍然告诉浏览器已发送，避免用登录页探测号码。 */
    public void sendLoginCode(String phone) {
        try {
            var request = HttpRequest.newBuilder(URI.create(requireBase() + "/auth/sms/send"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                            "phone", phone, "scene", "login", "app", "keel"))))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 300 || GatewayErrors.notRegistered(response.statusCode(), response.body())) return;
            throw GatewayErrors.translate(response.statusCode(), response.body());
        } catch (KeelException e) {
            throw e;
        } catch (Exception e) {
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, ErrorCode.AUTH_GATEWAY_UNAVAILABLE.message());
        }
    }

    public Captcha captcha() {
        try {
            var request = HttpRequest.newBuilder(URI.create(requireBase() + "/auth/captcha"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) throw GatewayErrors.translate(response.statusCode(), response.body());
            JsonNode node = json.readTree(response.body());
            return new Captcha(node.path("captchaImage").asText(""), node.path("challengeId").asText(""));
        } catch (KeelException e) {
            throw e;
        } catch (Exception e) {
            log.warn("auth-gateway login call failed: {}", e.toString());
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, ErrorCode.AUTH_GATEWAY_UNAVAILABLE.message());
        }
    }

    private Issued issued(JsonNode node) {
        return new Issued(node.path("access_token").asText(""), node.path("refresh_token").asText(""),
                node.path("expires_in").asLong(900), node.path("refresh_expires_in").asLong(604800));
    }

    private JsonNode postForm(String path, Map<String, String> form) {
        try {
            var request = HttpRequest.newBuilder(URI.create(requireBase() + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) throw GatewayErrors.translate(response.statusCode(), response.body());
            return json.readTree(response.body());
        } catch (KeelException e) {
            throw e;
        } catch (Exception e) {
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, ErrorCode.AUTH_GATEWAY_UNAVAILABLE.message());
        }
    }

    private String requireBase() {
        if (baseUrl.isBlank()) {
            log.warn("KEEL_AUTH_GATEWAY_URL is empty");
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, ErrorCode.AUTH_GATEWAY_UNAVAILABLE.message());
        }
        return baseUrl;
    }

    private static Map<String, String> client(String clientId, String assertion) {
        return Map.of(
                "client_id", clientId,
                "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion", assertion);
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    public record Issued(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn) {}

    public record Captcha(String captchaImage, String challengeId) {}
}
