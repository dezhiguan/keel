package com.keel.server.prompt;

import com.keel.server.release.PromptDriftJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class PromptFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<Ver>> STORE = new ConcurrentHashMap<>();
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger PRODUCTION_PATCHES = new AtomicInteger();
    private static volatile boolean failSecondProductionPatch;
    private static final int PORT;

    static {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", PromptFlowTest::handle);
            server.start();
            PORT = server.getAddress().getPort();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void langfuse(DynamicPropertyRegistry registry) {
        registry.add("LANGFUSE_HOST", () -> "http://127.0.0.1:" + PORT);
        registry.add("LANGFUSE_PUBLIC_KEY", () -> "pk");
        registry.add("LANGFUSE_SECRET_KEY", () -> "sk");
        registry.add("LANGFUSE_PROJECT_ID", () -> "keel");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PromptDriftJob drift;

    @BeforeEach
    void reset() {
        STORE.clear();
        CALLS.clear();
        PRODUCTION_PATCHES.set(0);
        failSecondProductionPatch = false;
        jdbc.update("DELETE FROM prompt_promotion WHERE agent_name = 'prompt-agent'");
        jdbc.update("DELETE FROM release_record WHERE agent_name = 'prompt-agent'");
        jdbc.update("DELETE FROM console_audit_event WHERE agent = 'prompt-agent'");
        jdbc.update("DELETE FROM agent_version WHERE agent_name = 'prompt-agent'");
        jdbc.update("DELETE FROM agent WHERE name = 'prompt-agent'");
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness)
                VALUES ('prompt-agent', '提示词测试', 'AGENT', 'code', 'python', '平台', 'dev', 'ONLINE', 'k8s')
                """);
        jdbc.update("""
                INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash, released_by)
                VALUES ('prompt-agent', 'v1', 'dev', ?::jsonb, 'hash', 'test')
                """, """
                {"spec":{"prompts":{"items":[{"name":"answer","type":"text"},{"name":"route","type":"text"}]}}}
                """);
    }

    @Test void savePostsAVersionAndKeepsTheTextOutOfAudit() throws Exception {
        save("answer", "UNIQUE_PROMPT_TEXT_ZX");
        assertThat(CALLS).anyMatch(call -> call.startsWith("POST "));
        assertThat(CALLS).noneMatch(call -> call.startsWith("PATCH "));
        assertThat(CALLS).anyMatch(call -> call.contains("%2F"));
        var payload = payload("prompt:prompt-agent/answer");
        assertThat(payload).doesNotContain("UNIQUE_PROMPT_TEXT_ZX");
        assertThat(payload).contains("sha256");
    }

    @Test void promoteProdIsRejectedBeforeAnyPatch() throws Exception {
        save("answer", "hello");
        mvc.perform(post("/api/v1/agents/prompt-agent/prompts/answer/promote")
                        .contentType("application/json")
                        .content("{\"env\":\"prod\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SERVER_INVALID_PARAM"));
        assertThat(CALLS).noneMatch(call -> call.startsWith("PATCH "));
    }

    @Test void promoteDevPatchesOnceAndSkipsThePromotionTable() throws Exception {
        save("answer", "hello");
        CALLS.clear();
        mvc.perform(post("/api/v1/agents/prompt-agent/prompts/answer/promote")
                        .contentType("application/json")
                        .content("{\"env\":\"dev\",\"version\":1}"))
                .andExpect(status().isOk());
        assertThat(CALLS.stream().filter(call -> call.startsWith("PATCH ")).count()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM prompt_promotion WHERE agent_name = 'prompt-agent'")).isZero();
        assertThat(count("SELECT count(*) FROM console_audit_event WHERE resource = 'prompt:prompt-agent/answer' AND env = 'dev'")).isEqualTo(1);
    }

    @Test void stagingPromotionBecomesInvalidWhenTheGateVersionDiffers() throws Exception {
        save("answer", "hello");
        promote("answer", "staging", 1);
        assertThat(count("SELECT count(*) FROM prompt_promotion WHERE gate_status = 'PENDING'")).isEqualTo(1);
        mvc.perform(post("/api/v1/gate-results").contentType("application/json")
                        .content("{\"agent\":\"prompt-agent\",\"gateRunId\":\"g1\",\"passed\":true,\"promptVersions\":{\"answer\":9}}"))
                .andExpect(jsonPath("$.data.gate").value("invalid"));
        assertThat(count("SELECT count(*) FROM prompt_promotion WHERE gate_status = 'INVALID'")).isEqualTo(1);
    }

    @Test void releaseWithoutAPassedPromotionDoesNotPatch() throws Exception {
        save("answer", "hello");
        promote("answer", "staging", 1);
        var before = CALLS.stream().filter(call -> call.startsWith("PATCH ")).count();
        mvc.perform(post("/api/v1/agents/prompt-agent/releases").contentType("application/json")
                        .content("{\"env\":\"prod\",\"gateRunId\":\"g1\",\"image\":\"prompt:v1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMPT_NOT_GATED"));
        assertThat(CALLS.stream().filter(call -> call.startsWith("PATCH ")).count()).isEqualTo(before);
    }

    @Test void releaseMovesProductionToTheStagingVersionWithoutCreatingOne() throws Exception {
        save("answer", "hello");
        promote("answer", "staging", 1);
        pass("answer", 1);
        var posts = CALLS.stream().filter(call -> call.startsWith("POST ")).count();
        mvc.perform(post("/api/v1/agents/prompt-agent/releases").contentType("application/json")
                        .content("{\"env\":\"prod\",\"gateRunId\":\"g1\",\"image\":\"prompt:v1\"}"))
                .andExpect(status().isOk());
        assertThat(CALLS.stream().filter(call -> call.startsWith("POST ")).count()).isEqualTo(posts);
        assertThat(label("prompt-agent/answer", "production")).isEqualTo(1);
        mvc.perform(post("/api/v1/agents/prompt-agent/prompts/answer/rollback")
                        .contentType("application/json")
                        .content("{\"version\":99,\"reason\":\"no\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMPT_ROLLBACK_TARGET"));
    }

    @Test void aFailedSecondProductionMoveIsReverted() throws Exception {
        save("answer", "one");
        save("route", "two");
        promote("answer", "staging", 1);
        promote("route", "staging", 1);
        mvc.perform(post("/api/v1/gate-results").contentType("application/json")
                        .content("{\"agent\":\"prompt-agent\",\"gateRunId\":\"ok\",\"passed\":true,\"promptVersions\":{\"answer\":1,\"route\":1}}"))
                .andExpect(status().isOk());
        failSecondProductionPatch = true;
        mvc.perform(post("/api/v1/agents/prompt-agent/releases").contentType("application/json")
                        .content("{\"env\":\"prod\",\"gateRunId\":\"g2\",\"image\":\"prompt:v1\"}"))
                .andExpect(status().isInternalServerError());
        assertThat(label("prompt-agent/answer", "production")).isNull();
        assertThat(label("prompt-agent/route", "production")).isNull();
    }

    @Test void syncingTheSameFileTwiceCreatesOneVersion() throws Exception {
        var sha = PromptTexts.sha256(JSON.getNodeFactory().textNode("from-git"), "text");
        var body = """
                {"gitSha":"abc123","files":[{"name":"answer","type":"text","sha256":"%s","prompt":"from-git"}]}
                """.formatted(sha);
        mvc.perform(post("/api/v1/agents/prompt-agent/prompts/sync").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created[0].version").value(1));
        var posts = CALLS.stream().filter(call -> call.startsWith("POST ")).count();
        mvc.perform(post("/api/v1/agents/prompt-agent/prompts/sync").contentType("application/json").content(body))
                .andExpect(jsonPath("$.data.created.length()").value(0));
        assertThat(CALLS.stream().filter(call -> call.startsWith("POST ")).count()).isEqualTo(posts);
    }

    @Test void driftAlertsForProductionButNotForTest() throws Exception {
        save("answer", "v1");
        save("answer", "v2");
        promote("answer", "staging", 1);
        pass("answer", 1);
        mvc.perform(post("/api/v1/agents/prompt-agent/releases").contentType("application/json")
                        .content("{\"env\":\"prod\",\"gateRunId\":\"g3\",\"image\":\"prompt:v1\"}"))
                .andExpect(status().isOk());
        relabel("prompt-agent/answer", 2, "test");
        assertThat(drift.scan()).isZero();
        relabel("prompt-agent/answer", 2, "production");
        assertThat(drift.scan()).isEqualTo(1);
        assertThat(drift.scan()).isZero();
        var payload = payload("prompt:prompt-agent/answer");
        assertThat(payload).contains("drift");
        assertThat(payload).doesNotContain("v2-text").doesNotContain("\"prompt\"");
    }

    private void save(String name, String text) throws Exception {
        mvc.perform(post("/api/v1/agents/prompt-agent/prompts/" + name + "/versions")
                        .contentType("application/json")
                        .content("{\"prompt\":\"" + text + "\",\"commitMessage\":\"save " + text + "\"}"))
                .andExpect(status().isOk());
    }

    private void promote(String name, String env, int version) throws Exception {
        mvc.perform(post("/api/v1/agents/prompt-agent/prompts/" + name + "/promote")
                        .contentType("application/json")
                        .content("{\"env\":\"" + env + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private void pass(String name, int version) throws Exception {
        mvc.perform(post("/api/v1/gate-results").contentType("application/json")
                        .content("{\"agent\":\"prompt-agent\",\"gateRunId\":\"ok\",\"passed\":true,\"promptVersions\":{\"" + name + "\":" + version + "}}"))
                .andExpect(status().isOk());
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private String payload(String resource) {
        var rows = jdbc.query("SELECT payload::text FROM console_audit_event WHERE resource = ? ORDER BY ts DESC",
                (rs, n) -> rs.getString(1), resource);
        return rows.isEmpty() || rows.getFirst() == null ? "" : rows.getFirst();
    }

    private static Integer label(String name, String label) {
        return STORE.getOrDefault(name, List.of()).stream()
                .filter(version -> version.labels.contains(label))
                .map(version -> version.version)
                .findFirst()
                .orElse(null);
    }

    private static void relabel(String name, int version, String label) {
        STORE.getOrDefault(name, List.of()).forEach(item -> item.labels.remove(label));
        STORE.getOrDefault(name, List.of()).stream().filter(item -> item.version == version).findFirst()
                .ifPresent(item -> item.labels.add(label));
    }

    private static void handle(HttpExchange exchange) throws java.io.IOException {
        var method = exchange.getRequestMethod();
        var raw = exchange.getRequestURI().getRawPath();
        var query = exchange.getRequestURI().getRawQuery();
        CALLS.add(method + " " + raw);
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("GET".equals(method) && "/api/public/v2/prompts".equals(raw)) {
            send(exchange, 200, list(query));
            return;
        }
        if ("POST".equals(method) && "/api/public/v2/prompts".equals(raw)) {
            send(exchange, 200, create(body));
            return;
        }
        var path = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        if (!path.startsWith("/api/public/v2/prompts/")) {
            send(exchange, 404, "{}");
            return;
        }
        var rest = path.substring("/api/public/v2/prompts/".length());
        if (rest.contains("/versions/")) {
            var name = rest.substring(0, rest.indexOf("/versions/"));
            var version = Integer.parseInt(rest.substring(rest.lastIndexOf('/') + 1));
            if ("PATCH".equals(method) && body.contains("\"production\"") && failSecondProductionPatch && PRODUCTION_PATCHES.incrementAndGet() == 2) {
                send(exchange, 500, "{}");
                return;
            }
            if ("PATCH".equals(method)) {
                patch(name, version, body);
                send(exchange, 200, "{}");
                return;
            }
        }
        if ("GET".equals(method)) {
            var found = find(rest, query);
            send(exchange, found == null ? 404 : 200, found == null ? "{}" : found);
            return;
        }
        send(exchange, 404, "{}");
    }

    private static String list(String query) {
        var name = param(query, "name");
        var label = param(query, "label");
        var data = JSON.createArrayNode();
        STORE.forEach((prompt, versions) -> {
            if (name != null && !name.equals(prompt)) {
                return;
            }
            if (label != null && versions.stream().noneMatch(version -> version.labels.contains(label))) {
                return;
            }
            ObjectNode row = JSON.createObjectNode();
            row.put("name", prompt);
            row.put("type", versions.isEmpty() ? "text" : versions.getLast().type);
            var nums = row.putArray("versions");
            var labels = row.putArray("labels");
            versions.forEach(version -> {
                nums.add(version.version);
                version.labels.forEach(labels::add);
            });
            data.add(row);
        });
        var body = JSON.createObjectNode();
        body.set("data", data);
        body.putObject("meta").put("totalPages", 1);
        return body.toString();
    }

    private static String create(String raw) {
        try {
            var body = JSON.readTree(raw);
            var name = body.path("name").asText();
            var versions = STORE.computeIfAbsent(name, key -> new ArrayList<>());
            var version = versions.stream().mapToInt(item -> item.version).max().orElse(0) + 1;
            var labels = new ArrayList<String>();
            body.path("labels").forEach(label -> labels.add(label.asText()));
            var stored = new Ver(version, body.path("type").asText("text"), body.get("prompt"), labels,
                    body.path("commitMessage").asText(""), Instant.now().toString());
            versions.add(stored);
            return stored.json().toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void patch(String name, int version, String raw) {
        try {
            var labels = new ArrayList<String>();
            JSON.readTree(raw).path("newLabels").forEach(label -> labels.add(label.asText()));
            for (var item : STORE.getOrDefault(name, List.of())) {
                if (item.version == version) {
                    item.labels.clear();
                    item.labels.addAll(labels);
                } else {
                    item.labels.removeAll(labels);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String find(String name, String query) {
        var version = param(query, "version");
        var label = param(query, "label");
        for (var item : STORE.getOrDefault(name, List.of())) {
            if (version != null && item.version == Integer.parseInt(version)) {
                return item.json().toString();
            }
            if (label != null && item.labels.contains(label)) {
                return item.json().toString();
            }
        }
        return null;
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (var part : query.split("&")) {
            var bits = part.split("=", 2);
            if (bits.length == 2 && bits[0].equals(key)) {
                return URLDecoder.decode(bits[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class Ver {
        final int version;
        final String type;
        final JsonNode prompt;
        final List<String> labels;
        final String commitMessage;
        final String createdAt;

        Ver(int version, String type, JsonNode prompt, List<String> labels, String commitMessage, String createdAt) {
            this.version = version;
            this.type = type;
            this.prompt = prompt;
            this.labels = labels;
            this.commitMessage = commitMessage;
            this.createdAt = createdAt;
        }

        ObjectNode json() {
            var body = JSON.createObjectNode();
            body.put("name", "");
            body.put("version", version);
            body.put("type", type);
            body.set("prompt", prompt);
            body.putObject("config");
            var arr = body.putArray("labels");
            labels.forEach(arr::add);
            body.put("commitMessage", commitMessage);
            body.put("createdAt", createdAt);
            body.put("createdBy", "api");
            return body;
        }
    }
}
