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
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Langfuse public API. Does not call the removed dataset-run-items route. */
public class LangfuseClient {
    private final String baseUrl;
    private final String authorization;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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

    /**
     * Observation count over the window. Null when Langfuse cannot be read.
     * v2 metrics has no traces view; this counts observations.
     */
    public JsonNode observationsByTrace(String traceId) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        return get("/api/public/v2/observations?limit=100&fields=core,basic,io,metadata&traceId="
                + URLEncoder.encode(traceId, StandardCharsets.UTF_8));
    }

    public JsonNode experiments() {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        return get("/api/public/experiments?fromStartTime=2020-01-01T00:00:00.000Z&limit=50&fields=core,scores");
    }

    public JsonNode experimentItems(String experimentId) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        return get("/api/public/experiment-items?fromStartTime=2020-01-01T00:00:00.000Z&limit=100&fields=scores&experimentId="
                + URLEncoder.encode(experimentId, StandardCharsets.UTF_8));
    }

    public JsonNode scores() {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        return get("/api/public/v3/scores");
    }

    public JsonNode observationsPage() {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        return get("/api/public/v2/observations?limit=100&fields=core,basic,io,metadata");
    }

    /**
     * Root observations in the window. Null when Langfuse cannot be read.
     * One page is not enough once a project has more than 100 roots, so follow the cursor.
     */
    public List<RootCall> rootCalls(Instant from, Instant to) {
        var page = roots(from, to);
        return page == null ? null : page.rows();
    }

    /**
     * Root observations in the window. Null when Langfuse cannot be read.
     * {@code complete} is false when the scan stops at 2000 rows, so a total derived from it would be short.
     */
    public RootPage roots(Instant from, Instant to) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            return null;
        }
        try {
            var rows = new ArrayList<RootCall>();
            String cursor = null;
            var complete = true;
            for (int page = 0; page < 20; page++) {
                var path = "/api/public/v2/observations?fields=core,time,metadata&isRootObservation=true&limit=100"
                        + "&fromStartTime=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                        + "&toStartTime=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
                if (cursor != null) {
                    path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
                }
                var body = get(path);
                body.path("data").forEach(row -> rows.add(new RootCall(
                        row.path("traceId").asText(""),
                        text(row.path("metadata"), "keel.agent"),
                        text(row.path("metadata"), "keel.llm.key_alias"),
                        seconds(row))));
                var next = body.path("meta").path("cursor").asText("");
                if (next.isBlank() || next.equals(cursor)) {
                    break;
                }
                if (page == 19) {
                    complete = false;
                }
                cursor = next;
            }
            return new RootPage(rows, complete);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double seconds(JsonNode row) {
        var start = row.path("startTime").asText("");
        var end = row.path("endTime").asText("");
        if (start.isBlank() || end.isBlank()) {
            return null;
        }
        try {
            var millis = java.time.Duration.between(java.time.Instant.parse(start), java.time.Instant.parse(end)).toMillis();
            return millis < 0 ? null : millis / 1000.0;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Integer observationCount(Instant from, Instant to) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            return null;
        }
        try {
            var query = "{\"view\":\"observations\",\"metrics\":[{\"measure\":\"count\",\"aggregation\":\"count\"}],"
                    + "\"dimensions\":[],\"filters\":[],\"fromTimestamp\":\"" + from + "\",\"toTimestamp\":\"" + to + "\"}";
            var body = get("/api/public/v2/metrics?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            var count = body.path("data").path(0).path("count_count");
            if (count.isMissingNode() || count.isNull()) {
                return 0;
            }
            return Integer.valueOf(count.asText("0"));
        } catch (RuntimeException e) {
            return null;
        }
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
                    .timeout(Duration.ofSeconds(20))
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

    private static String text(JsonNode metadata, String key) {
        var direct = metadata.path(key).asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        return metadata.path("attributes." + key).asText("");
    }

    public record RootCall(String traceId, String agent, String keyAlias, Double latencySeconds) {}

    public record RootPage(List<RootCall> rows, boolean complete) {}

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
