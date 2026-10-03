package com.keel.server.integration.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;

/** Langfuse public API. Does not call the removed dataset-run-items route. */
public class LangfuseClient {
    private final String baseUrl;
    private final String authorization;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public LangfuseClient(String baseUrl, String publicKey, String secretKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        if (publicKey == null || publicKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            this.authorization = "";
        } else {
            this.authorization = "Basic " + Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        }
    }

    public void importSeed(String dataset, Path seedFile) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        post("/api/public/datasets", json.createObjectNode().put("name", dataset), true);
        var existing = new HashSet<String>();
        var listed = get("/api/public/dataset-items?datasetName=" + dataset);
        listed.path("data").forEach(item -> existing.add(item.path("id").asText()));
        if (!Files.isRegularFile(seedFile)) {
            throw new IllegalStateException("找不到评测用例 " + seedFile);
        }
        try {
            for (var line : Files.readAllLines(seedFile)) {
                if (line.isBlank()) {
                    continue;
                }
                var item = json.readTree(line);
                var id = item.path("id").asText("");
                if (id.isBlank()) {
                    id = sha256(line);
                }
                if (!existing.add(id)) {
                    continue;
                }
                ObjectNode body = json.createObjectNode();
                body.put("id", id);
                body.put("datasetName", dataset);
                if (item.has("input")) {
                    body.set("input", item.get("input"));
                }
                if (item.has("expectedOutput")) {
                    body.set("expectedOutput", item.get("expectedOutput"));
                }
                post("/api/public/dataset-items", body, false);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void deleteDataset(String dataset) {
        send("DELETE", "/api/public/datasets/" + dataset, null, true);
    }

    private JsonNode get(String path) {
        return send("GET", path, null, false);
    }

    private void post(String path, JsonNode body, boolean ignoreConflict) {
        send("POST", path, body, ignoreConflict);
    }

    private JsonNode send(String method, String path, JsonNode body, boolean ignoreConflict) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", authorization);
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409 && ignoreConflict) {
                return json.createObjectNode();
            }
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Langfuse " + path + " " + response.statusCode());
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

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
