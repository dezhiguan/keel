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
import java.util.Locale;

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

    public boolean configured() {
        return !baseUrl.isBlank();
    }

    /** Null when the host is unset. True only when GET /api/public/health returns 2xx. */
    public Boolean healthy() {
        if (!configured()) {
            return null;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/public/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 300;
        } catch (Exception e) {
            return false;
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
        return get("/api/public/v2/observations?limit=100&fields=core,basic,io,metadata,model,usage&traceId="
                + URLEncoder.encode(traceId, StandardCharsets.UTF_8));
    }

    /** Dataset id/name pairs. v4 lists datasets at /v2/datasets; the unversioned path is the fallback. */
    public JsonNode datasets() {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        var data = json.createArrayNode();
        var base = "/api/public/v2/datasets";
        for (int page = 1; page <= 10; page++) {
            JsonNode body;
            try {
                body = get(base + "?page=" + page + "&limit=100");
            } catch (RuntimeException e) {
                if (page > 1 || !base.contains("/v2/")) {
                    break;
                }
                base = "/api/public/datasets";
                body = get(base + "?page=" + page + "&limit=100");
            }
            var rows = body.path("data");
            if (!rows.isArray() || rows.isEmpty()) {
                break;
            }
            rows.forEach(data::add);
            var totalPages = body.path("meta").path("totalPages").asInt(page);
            if (page >= totalPages) {
                break;
            }
        }
        var body = json.createObjectNode();
        body.set("data", data);
        return body;
    }

    public JsonNode experiments() {
        return experiments(List.of());
    }

    /**
     * Experiments newest-first. Pass dataset ids to ask Langfuse to filter; an empty list reads the project.
     * Core includes datasetId, not datasetName.
     */
    public JsonNode experiments(List<String> datasetIds) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        var filter = "";
        if (datasetIds != null && !datasetIds.isEmpty()) {
            filter = "&datasetId=" + URLEncoder.encode(String.join(",", datasetIds), StandardCharsets.UTF_8);
        }
        return pages("/api/public/experiments?fromStartTime=2020-01-01T00:00:00.000Z&limit=100&fields=core,scores" + filter);
    }

    public JsonNode experimentItems(String experimentId) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        return pages("/api/public/experiment-items?fromStartTime=2020-01-01T00:00:00.000Z&limit=100&fields=scores&experimentId="
                + URLEncoder.encode(experimentId, StandardCharsets.UTF_8));
    }

    private JsonNode pages(String path) {
        var data = json.createArrayNode();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            var next = path;
            if (cursor != null) {
                next += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            }
            var body = get(next);
            var rows = body.path("data");
            if (rows.isArray()) {
                rows.forEach(data::add);
            }
            var token = body.path("meta").path("cursor").asText("");
            if (token.isBlank() || token.equals(cursor) || !rows.isArray() || rows.isEmpty()) {
                break;
            }
            cursor = token;
        }
        var body = json.createObjectNode();
        body.set("data", data);
        return body;
    }

    public JsonNode scores() {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        return get("/api/public/v3/scores");
    }

    public JsonNode observationsPage() {
        return observationsBetween(null, null);
    }

    /**
     * Keel spans for the console trace list, in {@code [from, to)}.
     * Question, tokens and cost live on the generation, not the root, so the list groups these by traceId.
     * FastAPI probes export {@code GET /api/health} and {@code fastapi.*} into the same project. Observations
     * are newest-first, and the list only follows ten pages, so those probes hide yesterday's calls unless
     * the query keeps semantic types only. SPAN stays out: that is the probe traffic.
     * Follows the cursor for at most ten pages.
     */
    public JsonNode observationsBetween(Instant from, Instant to) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        var filter = "[{\"type\":\"stringOptions\",\"column\":\"type\",\"operator\":\"any of\","
                + "\"value\":[\"AGENT\",\"GENERATION\",\"TOOL\",\"RETRIEVER\",\"GUARDRAIL\",\"CHAIN\",\"EMBEDDING\",\"EVALUATOR\"]}]";
        var path = "/api/public/v2/observations?limit=100&fields=core,basic,io,metadata,model,usage&filter="
                + URLEncoder.encode(filter, StandardCharsets.UTF_8);
        if (from != null) {
            path += "&fromStartTime=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8);
        }
        if (to != null) {
            path += "&toStartTime=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
        }
        return pages(path);
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
     * First page of root observations. Hobby allows 30 general reads a minute, so overview does not follow the cursor.
     * {@code complete} is false when Langfuse still has another page.
     */
    public RootPage roots(Instant from, Instant to) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            return null;
        }
        try {
            var path = "/api/public/v2/observations?fields=core,time,metadata&isRootObservation=true&limit=100"
                    + "&fromStartTime=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                    + "&toStartTime=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
            var body = get(path);
            var data = body.path("data");
            var rows = new ArrayList<RootCall>();
            if (data.isArray()) {
                data.forEach(row -> rows.add(new RootCall(
                        row.path("traceId").asText(""),
                        text(row.path("metadata"), "keel.agent"),
                        text(row.path("metadata"), "keel.llm.key_alias"),
                        seconds(row))));
            }
            var more = !body.path("meta").path("cursor").asText("").isBlank();
            return new RootPage(rows, !more);
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

    /**
     * Generation observations in the window. Null when Langfuse cannot be read.
     * These are the model calls the SDK reported; the thin gateway does not report them.
     */
    public List<Generation> generations(Instant from, Instant to) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            return null;
        }
        try {
            var rows = new ArrayList<Generation>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                var path = "/api/public/v2/observations?fields=core,basic,time,metadata&limit=100"
                        + "&fromStartTime=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                        + "&toStartTime=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
                if (cursor != null) {
                    path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
                }
                var body = get(path);
                var data = body.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    break;
                }
                data.forEach(row -> {
                    if (!generation(row)) {
                        return;
                    }
                    var model = modelOf(row);
                    if (model.isBlank()) {
                        return;
                    }
                    rows.add(new Generation(
                            model,
                            text(row.path("metadata"), "keel.llm.key_alias"),
                            seconds(row),
                            timedOut(row),
                            text(row.path("metadata"), "keel.fallback_from")));
                });
                var next = body.path("meta").path("cursor").asText("");
                if (next.isBlank() || next.equals(cursor)) {
                    break;
                }
                cursor = next;
            }
            return rows;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Retriever observations and their stage children in the window. Null when Langfuse cannot be read.
     */
    public List<Retrieval> retrievals(Instant from, Instant to) {
        if (baseUrl.isBlank() || authorization.isBlank()) {
            return null;
        }
        try {
            var rows = new ArrayList<Retrieval>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                var path = "/api/public/v2/observations?fields=core,basic,time,metadata&limit=100"
                        + "&fromStartTime=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                        + "&toStartTime=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
                if (cursor != null) {
                    path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
                }
                var body = get(path);
                var data = body.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    break;
                }
                data.forEach(row -> {
                    if (!retrieval(row)) {
                        return;
                    }
                    rows.add(new Retrieval(
                            row.path("name").asText(""),
                            text(row.path("metadata"), "keel.agent"),
                            stageOf(row),
                            seconds(row)));
                });
                var next = body.path("meta").path("cursor").asText("");
                if (next.isBlank() || next.equals(cursor)) {
                    break;
                }
                cursor = next;
            }
            return rows;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean retrieval(JsonNode row) {
        if (!stageOf(row).isBlank()) {
            return true;
        }
        var type = row.path("type").asText("");
        if (type.equalsIgnoreCase("RETRIEVER")) {
            return true;
        }
        return "retriever".equals(text(row.path("metadata"), "langfuse.observation.type"));
    }

    private static String stageOf(JsonNode row) {
        var name = row.path("name").asText("").toLowerCase(Locale.ROOT);
        for (String stage : List.of("rewrite", "vector", "keyword", "rerank")) {
            if (name.equals(stage) || name.endsWith("." + stage) || name.endsWith("/" + stage)) {
                return stage;
            }
        }
        return "";
    }

    private static boolean generation(JsonNode row) {
        var type = row.path("type").asText("");
        if (type.equalsIgnoreCase("GENERATION")) {
            return true;
        }
        if ("generation".equals(text(row.path("metadata"), "langfuse.observation.type"))) {
            return true;
        }
        return type.isBlank() && "llm.chat".equals(row.path("name").asText("")) && !modelOf(row).isBlank();
    }

    private static String modelOf(JsonNode row) {
        var direct = row.path("model").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        var metadata = row.path("metadata");
        var named = text(metadata, "gen_ai.request.model");
        if (!named.isBlank()) {
            return named;
        }
        return text(metadata, "attributes.gen_ai.request.model");
    }

    private static boolean timedOut(JsonNode row) {
        var metadata = row.path("metadata");
        var blob = (row.path("statusMessage").asText("") + " " + row.path("level").asText("") + " "
                + text(metadata, "keel.status")).toLowerCase(Locale.ROOT);
        return blob.contains("timeout") || blob.contains("timed out") || blob.contains("超时");
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

    public boolean ready() {
        return configured() && !authorization.isBlank();
    }

    public JsonNode listPrompts(String name, String label) {
        if (!ready()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        var data = json.createArrayNode();
        for (int page = 1; page <= 5; page++) {
            var path = "/api/public/v2/prompts?page=" + page + "&limit=50";
            if (name != null && !name.isBlank()) {
                path += "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
            }
            if (label != null && !label.isBlank()) {
                path += "&label=" + URLEncoder.encode(label, StandardCharsets.UTF_8);
            }
            var body = get(path);
            var rows = body.path("data");
            if (!rows.isArray() || rows.isEmpty()) {
                break;
            }
            rows.forEach(data::add);
            if (page >= body.path("meta").path("totalPages").asInt(page)) {
                break;
            }
        }
        var out = json.createObjectNode();
        out.set("data", data);
        return out;
    }

    public JsonNode getPrompt(String name, Integer version, String label) {
        if (!ready()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        var path = "/api/public/v2/prompts/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        if (version != null) {
            path += "?version=" + version;
        } else if (label != null && !label.isBlank()) {
            path += "?label=" + URLEncoder.encode(label, StandardCharsets.UTF_8);
        }
        return send("GET", path, null, false, true);
    }

    public JsonNode createPrompt(String name, String type, JsonNode prompt, JsonNode config, String commitMessage, List<String> labels) {
        if (!ready()) {
            throw new IllegalStateException("Langfuse 地址或项目 Key 未配置");
        }
        var body = json.createObjectNode();
        body.put("name", name);
        body.put("type", type);
        body.set("prompt", prompt);
        if (config != null && config.size() > 0) {
            body.set("config", config);
        }
        if (commitMessage != null && !commitMessage.isBlank()) {
            body.put("commitMessage", commitMessage);
        }
        if (labels != null && !labels.isEmpty()) {
            var arr = body.putArray("labels");
            labels.forEach(arr::add);
        }
        return send("POST", "/api/public/v2/prompts", body, false, false);
    }

    public void moveLabel(String name, int version, String label) {
        var current = getPrompt(name, version, null);
        if (current == null) {
            throw new IllegalStateException("Langfuse 提示词版本不存在 " + name + " v" + version);
        }
        var labels = new ArrayList<String>();
        current.path("labels").forEach(node -> {
            var text = node.asText("");
            if (!text.isBlank() && !"latest".equals(text) && !labels.contains(text)) {
                labels.add(text);
            }
        });
        if (!labels.contains(label)) {
            labels.add(label);
        }
        patchLabels(name, version, labels);
    }

    public void removeLabel(String name, int version, String label) {
        var current = getPrompt(name, version, null);
        if (current == null) {
            return;
        }
        var labels = new ArrayList<String>();
        current.path("labels").forEach(node -> {
            var text = node.asText("");
            if (!text.isBlank() && !"latest".equals(text) && !text.equals(label)) {
                labels.add(text);
            }
        });
        patchLabels(name, version, labels);
    }

    private void patchLabels(String name, int version, List<String> labels) {
        var body = json.createObjectNode();
        var arr = body.putArray("newLabels");
        labels.forEach(arr::add);
        send("PATCH", "/api/public/v2/prompts/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/versions/" + version, body, false, false);
    }

    private JsonNode get(String path) {
        return send("GET", path, null, false, false);
    }

    private void post(String path, JsonNode body, boolean ignoreConflict) {
        send("POST", path, body, ignoreConflict, false);
    }

    private JsonNode send(String method, String path, JsonNode body, boolean ignoreConflict) {
        return send(method, path, body, ignoreConflict, false);
    }

    private JsonNode send(String method, String path, JsonNode body, boolean ignoreConflict, boolean allowNotFound) {
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
            if (response.statusCode() == 404 && allowNotFound) {
                return null;
            }
            if (response.statusCode() == 409 && ignoreConflict) {
                return json.createObjectNode();
            }
            if (response.statusCode() == 429) {
                throw new LangfuseRateLimit(retryAfter(response));
            }
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Langfuse " + path + " " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(response.body());
        } catch (LangfuseRateLimit e) {
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int retryAfter(HttpResponse<String> response) {
        var header = response.headers().firstValue("Retry-After").orElse("");
        try {
            var seconds = Integer.parseInt(header.trim());
            return seconds > 0 ? seconds : 60;
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private static String text(JsonNode metadata, String key) {
        var direct = metadata.path(key).asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        var dotted = metadata.path("attributes." + key).asText("");
        if (!dotted.isBlank()) {
            return dotted;
        }
        var nested = metadata.path("attributes").path(key).asText("");
        if (!nested.isBlank()) {
            return nested;
        }
        return metadata.path("resourceAttributes").path(key).asText("");
    }

    public record RootCall(String traceId, String agent, String keyAlias, Double latencySeconds) {}

    public record Generation(String model, String keyAlias, Double latencySeconds, boolean timedOut, String fallbackFrom) {}

    public record Retrieval(String name, String agent, String stage, Double latencySeconds) {}

    public record RootPage(List<RootCall> rows, boolean complete) {}

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
