package com.keel.server.integration.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls an agent's own /v1/health, /v1/manifest, /v1/invoke, and /v1/runs/{id}/resume. */
@Component
public class AgentEndpointClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public boolean healthy(String endpoint) {
        var response = send(endpoint, "/v1/health");
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    public Answer invoke(String endpoint, String text) {
        return invoke(endpoint, text, null);
    }

    public Answer invoke(String endpoint, String text, String bridgeToken) {
        return invoke(endpoint, text, bridgeToken, null, null);
    }

    public Answer invoke(String endpoint, String text, String bridgeToken, String agent, String env) {
        var base = trim(endpoint);
        try {
            var payload = json.createObjectNode();
            payload.putObject("input").put("text", text);
            var builder = HttpRequest.newBuilder(URI.create(base + "/v1/invoke"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json");
            if (bridgeToken != null && !bridgeToken.isBlank()) {
                builder.header("X-Keel-Bridge", bridgeToken);
            }
            if (agent != null && !agent.isBlank()) {
                builder.header("X-Keel-Agent", agent);
            }
            if (env != null && !env.isBlank()) {
                builder.header("X-Keel-Env", env);
            }
            var request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("智能体返回 HTTP " + response.statusCode());
            }
            return parseFinal(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用智能体失败");
        }
    }

    static Answer parseFinal(String body) {
        String event = "";
        String answer = null;
        String traceId = null;
        for (var line : body.split("\n")) {
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:") && "final".equals(event)) {
                try {
                    var node = new ObjectMapper().readTree(line.substring("data:".length()).trim());
                    answer = node.path("answer").asText("");
                    traceId = node.path("trace_id").asText("");
                } catch (Exception e) {
                    throw new IllegalStateException("智能体的 final 事件无法解析");
                }
            } else if (line.startsWith("data:") && "error".equals(event)) {
                throw new IllegalStateException("智能体返回错误 " + line.substring("data:".length()).trim());
            }
        }
        if (answer == null) {
            throw new IllegalStateException("智能体没有返回 final 事件");
        }
        return new Answer(answer, traceId == null || traceId.isBlank() ? null : traceId);
    }

    public record Answer(String text, String traceId) {}

    public void resume(String endpoint, String runId, String resumeToken, String decision, String inputText) {
        var payload = json.createObjectNode();
        payload.put("resume_token", resumeToken == null ? "" : resumeToken);
        if (decision != null) {
            payload.put("decision", decision);
        }
        if (inputText != null) {
            payload.putObject("input").put("text", inputText);
        }
        var base = trim(endpoint);
        try {
            var request = HttpRequest.newBuilder(URI.create(base + "/v1/runs/" + runId + "/resume"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("智能体恢复返回 HTTP " + response.statusCode());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用智能体恢复失败");
        }
    }

    public String manifestVersion(String endpoint) {
        var response = send(endpoint, "/v1/manifest");
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("manifest " + response.statusCode());
        }
        try {
            return json.readTree(response.body()).path("version").asText("");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private HttpResponse<String> send(String endpoint, String path) {
        var base = trim(endpoint);
        try {
            var request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
