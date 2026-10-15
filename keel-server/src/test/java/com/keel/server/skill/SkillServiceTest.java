package com.keel.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SkillServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void riskFollowsTheHighestDependentTool() {
        assertThat(SkillService.highest(List.of("LOW", "MID"))).isEqualTo("MID");
        assertThat(SkillService.highest(List.of("MID", "HIGH", "LOW"))).isEqualTo("HIGH");
        assertThat(SkillService.highest(List.of())).isEqualTo("LOW");
    }

    @Test void grantingASkillDoesNotNameAToolGrantTable() {
        assertThat(SkillService.INSERT_GRANT).doesNotContain("agent_tool_grant");
        assertThat(SkillService.INSERT_GRANT).contains("agent_skill_grant");
    }

    @Test void theSameSkillCaseIsPostedOnce() throws Exception {
        var posts = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.add(exchange.getRequestURI().getPath() + " " + body);
            }
            var response = exchange.getRequestURI().getPath().contains("dataset-items") && "GET".equals(exchange.getRequestMethod())
                    ? items(posts) : "{}";
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var langfuse = new LangfuseClient("http://127.0.0.1:" + server.getAddress().getPort(), "pk", "sk");
            var first = langfuse.addSkillCase("askdb/smoke", "cite-from-kb", 1, "case-1", "问题", "无法确认");
            var second = langfuse.addSkillCase("askdb/smoke", "cite-from-kb", 1, "case-1", "问题", "无法确认");
            assertThat(second).isEqualTo(first);
            var itemPosts = posts.stream().filter(line -> line.startsWith("/api/public/dataset-items")).toList();
            assertThat(itemPosts).hasSize(1);
            var saved = JSON.readTree(itemPosts.getFirst().substring(itemPosts.getFirst().indexOf(' ') + 1));
            assertThat(saved.path("metadata").path("source").asText()).isEqualTo("skill");
            assertThat(saved.path("metadata").path("skill").asText()).isEqualTo("cite-from-kb");
            assertThat(saved.path("datasetName").asText()).isEqualTo("askdb/smoke");
        } finally {
            server.stop(0);
        }
    }

    private static String items(List<String> posts) {
        var data = JSON.createArrayNode();
        for (var line : posts) {
            if (!line.startsWith("/api/public/dataset-items")) {
                continue;
            }
            try {
                var body = JSON.readTree(line.substring(line.indexOf(' ') + 1));
                data.addObject().put("id", body.path("id").asText());
            } catch (Exception ignored) {
                // Skip a body that is not JSON.
            }
        }
        return JSON.createObjectNode().set("data", data).toString();
    }
}
