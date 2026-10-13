package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsoleCookies;
import com.keel.server.common.KeelException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Issues a read-only rag-forge API key for the knowledge bases selected at registration.
 * The caller is the console user's access token, not the metrics account.
 */
@Component
public class RagForgeAccess {
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public RagForgeAccess(@Value("${RAGFORGE_BASE_URL:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
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
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "当前账号不能访问知识库：" + String.join("、", missing));
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

    private static String bearer() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return "";
        }
        HttpServletRequest request = servlet.getRequest();
        var token = ConsoleCookies.read(request, ConsoleCookies.names(false), true);
        return token == null ? "" : token;
    }
}
