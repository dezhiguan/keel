package com.keel.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.auth.ConsolePrincipal;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class SkillFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CopyOnWriteArrayList<String> POSTS = new CopyOnWriteArrayList<>();
    private static final int PORT;

    static {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", SkillFlowTest::handle);
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
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SkillService skills;

    @BeforeEach
    void reset() {
        POSTS.clear();
        jdbc.update("DELETE FROM agent_skill_grant WHERE agent_name = 'skill-agent'");
        jdbc.update("DELETE FROM skill_version WHERE skill_name = 'cite-from-kb'");
        jdbc.update("DELETE FROM skill WHERE name = 'cite-from-kb'");
        jdbc.update("DELETE FROM agent_tool_grant WHERE agent_name = 'skill-agent'");
        jdbc.update("DELETE FROM tool_version WHERE tool_name = 'rag.search'");
        jdbc.update("DELETE FROM tool WHERE name = 'rag.search'");
        jdbc.update("DELETE FROM agent_version WHERE agent_name = 'skill-agent'");
        jdbc.update("DELETE FROM agent WHERE name = 'skill-agent'");
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness)
                VALUES ('skill-agent', '技能测试', 'AGENT', 'code', 'python', '平台', 'dev', 'ONLINE', 'k8s')
                """);
        jdbc.update("""
                INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash, released_by)
                VALUES ('skill-agent', 'v1', 'dev', ?::jsonb, 'hash', 'test')
                """, """
                {"spec":{"eval":{"dataset":"skill-agent/smoke"},"tools":[]}}
                """);
        jdbc.update("""
                INSERT INTO tool (name, scope, owner_org, provider, status)
                VALUES ('rag.search', 'SHARED', '平台', 'mcp://rag', 'ONLINE')
                """);
        jdbc.update("""
                INSERT INTO tool_version (tool_name, version, schema_json, description, access, risk)
                VALUES ('rag.search', 'v1', '{}', '检索', 'READ', 'MID')
                """);
    }

    @Test void grantingASkillDoesNotGrantItsToolsAndMergesTheCaseOnce() throws Exception {
        mvc.perform(post("/api/v1/skills").contentType("application/json").content("""
                {"name":"cite-from-kb","ownerOrg":"平台组","description":"只依据检索作答","instructions":"找不到依据时说无法确认",
                 "tools":["rag.search"],"examples":["结论要标出处"],
                 "cases":[{"id":"case-1","input":"没有资料时怎么说","expectedOutput":"无法确认"}]}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        var before = count("SELECT count(*) FROM agent_tool_grant WHERE agent_name = 'skill-agent'");
        mvc.perform(post("/api/v1/agents/skill-agent/skills").contentType("application/json")
                        .content("{\"name\":\"cite-from-kb\",\"version\":1}")
                        .with(request -> {
                            ConsolePrincipal.set(request, ConsolePrincipal.dev());
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolsGranted.length()").value(0))
                .andExpect(jsonPath("$.data.casesMerged").value(1));
        assertThat(count("SELECT count(*) FROM agent_tool_grant WHERE agent_name = 'skill-agent'")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM agent_skill_grant WHERE agent_name = 'skill-agent' AND skill_name = 'cite-from-kb'")).isEqualTo(1);
        mvc.perform(post("/api/v1/agents/skill-agent/skills").contentType("application/json")
                        .content("{\"name\":\"cite-from-kb\",\"version\":1}")
                        .with(request -> {
                            ConsolePrincipal.set(request, ConsolePrincipal.dev());
                            return request;
                        }))
                .andExpect(status().isOk());
        assertThat(POSTS.stream().filter(line -> line.startsWith("/api/public/dataset-items")).count()).isEqualTo(1);
        mvc.perform(get("/api/v1/agents/skill-agent/skills/cite-from-kb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instructions").value("找不到依据时说无法确认"))
                .andExpect(jsonPath("$.data.tools[0]").value("rag.search"));
        mvc.perform(get("/api/v1/skills"))
                .andExpect(jsonPath("$.data.items[0].risk").value("MID"))
                .andExpect(jsonPath("$.data.items[0].users[0]").value("skill-agent"));
    }

    @Test void aMixedCaseNameIsStoredLikeThePrototype() throws Exception {
        mvc.perform(post("/api/v1/skills").contentType("application/json").content("""
                {"name":" Cite-From-KB ","ownerOrg":"平台组","description":"引用","instructions":"写出出处"}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("cite-from-kb"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test void editingKeepsOldVersionsAndPromotesOnlyAfterRegression() throws Exception {
        jdbc.update("DELETE FROM eval_run WHERE agent_name = 'skill-agent'");
        publish("""
                {"name":"cite-from-kb","ownerOrg":"平台组","description":"只依据检索作答","instructions":"找不到依据时说无法确认",
                 "tools":["rag.search"],"examples":["结论要标出处"],"cases":[{"input":"没有资料时怎么说","expectedOutput":"无法确认"}]}
                """, 1);
        mvc.perform(post("/api/v1/skills").contentType("application/json").content("""
                {"name":"cite-from-kb","ownerOrg":"平台组","description":"只依据检索作答","instructions":"引用段落号",
                 "note":"","tools":["rag.search"],"examples":["结论要标出处"],"cases":[]}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("变更说明不能为空"));
        mvc.perform(post("/api/v1/skills").contentType("application/json").content("""
                {"name":"cite-from-kb","ownerOrg":"平台组","description":"只依据检索作答","instructions":"找不到依据时说无法确认",
                 "note":"没改","tools":["rag.search"],"examples":["结论要标出处"],
                 "cases":[{"input":"没有资料时怎么说","expectedOutput":"无法确认"}]}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("内容没有变化，不生成新版本"));
        publish("""
                {"name":"cite-from-kb","ownerOrg":"平台组","description":"只依据检索作答","instructions":"引用段落号",
                 "note":"引用必须带段落号","tools":["rag.search"],"examples":["结论要标出处","没有依据就拒答"],
                 "cases":[{"input":"没有资料时怎么说","expectedOutput":"无法确认"},{"input":"段落怎么写","expectedOutput":"写出段落号"}]}
                """, 2);
        grant(1);
        mvc.perform(get("/api/v1/skills/cite-from-kb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latest").value(2))
                .andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[0].instructions").value("找不到依据时说无法确认"))
                .andExpect(jsonPath("$.data.versions[1].examples.length()").value(2))
                .andExpect(jsonPath("$.data.versions[1].cases.length()").value(2))
                .andExpect(jsonPath("$.data.versions[1].note").value("引用必须带段落号"))
                .andExpect(jsonPath("$.data.users[0].version").value(1))
                .andExpect(jsonPath("$.data.users[0].status").value("BEHIND"));
        var toolsBefore = count("SELECT count(*) FROM agent_tool_grant WHERE agent_name = 'skill-agent'");
        skills.trackUpgrade("skill-agent", "cite-from-kb", "dev", "ev_skill_pass");
        assertThat(skillVersionInManifest()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM agent_skill_grant WHERE agent_name = 'skill-agent' AND version = 1")).isEqualTo(1);
        insertRun("ev_skill_pass", "DONE", "{\"passed\":true,\"scoreTotal\":0.92,\"gate\":{\"minScore\":0.85}}", null);
        var passed = mvc.perform(get("/api/v1/skills/cite-from-kb"))
                .andExpect(jsonPath("$.data.users[0].version").value(2))
                .andExpect(jsonPath("$.data.users[0].status").value("LATEST"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(passed).path("data").path("users").get(0).path("score").asDouble()).isCloseTo(0.92, within(0.001));
        publish("""
                {"name":"cite-from-kb","ownerOrg":"平台组","description":"只依据检索作答","instructions":"再补一句拒答时找谁",
                 "note":"补咨询人","tools":["rag.search"],"examples":["结论要标出处"],"cases":[]}
                """, 3);
        skills.trackUpgrade("skill-agent", "cite-from-kb", "dev", "ev_skill_fail");
        insertRun("ev_skill_fail", "FAILED", "{}", "回归失败");
        mvc.perform(get("/api/v1/skills/cite-from-kb"))
                .andExpect(jsonPath("$.data.users[0].version").value(2))
                .andExpect(jsonPath("$.data.users[0].status").value("BEHIND"))
                .andExpect(jsonPath("$.data.users[0].upgradeError").value("回归失败"));
        assertThat(skillVersionInManifest()).isEqualTo(2);
        mvc.perform(delete("/api/v1/agents/skill-agent/skills/cite-from-kb"))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM agent_skill_grant WHERE agent_name = 'skill-agent'")).isZero();
        assertThat(count("SELECT count(*) FROM agent_tool_grant WHERE agent_name = 'skill-agent'")).isEqualTo(toolsBefore);
        assertThat(count("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE constraint_type = 'FOREIGN KEY'
                  AND table_name IN ('skill_version', 'agent_skill_grant', 'user_memory')
                """)).isZero();
    }

    private void publish(String body, int version) throws Exception {
        mvc.perform(post("/api/v1/skills").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(version));
    }

    private void grant(int version) throws Exception {
        mvc.perform(post("/api/v1/agents/skill-agent/skills").contentType("application/json")
                        .content("{\"name\":\"cite-from-kb\",\"version\":" + version + "}")
                        .with(request -> {
                            ConsolePrincipal.set(request, ConsolePrincipal.dev());
                            return request;
                        }))
                .andExpect(status().isOk());
    }

    private void insertRun(String id, String state, String result, String error) {
        jdbc.update("""
                INSERT INTO eval_run(run_id, agent_name, env, namespace, source_pod, image, endpoint, dataset, requested_by, state, result, error)
                VALUES (?, 'skill-agent', 'dev', 'ns', 'pod', 'image', 'http://127.0.0.1', 'skill-agent/smoke', 'dev', ?, ?::jsonb, ?)
                """, id, state, result, error);
    }

    private int skillVersionInManifest() throws Exception {
        var raw = jdbc.queryForObject(
                "SELECT manifest_json::text FROM agent_version WHERE agent_name = 'skill-agent' AND env = 'dev'", String.class);
        var skills = JSON.readTree(raw).path("spec").path("skills");
        for (var item : skills) {
            if ("cite-from-kb".equals(item.path("name").asText())) {
                return item.path("version").asInt();
            }
        }
        return 0;
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static void handle(HttpExchange exchange) throws java.io.IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("POST".equals(exchange.getRequestMethod())) {
            POSTS.add(exchange.getRequestURI().getPath() + " " + body);
        }
        var response = "{}";
        if ("GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().contains("dataset-items")) {
            var data = JSON.createArrayNode();
            for (var line : POSTS) {
                if (!line.startsWith("/api/public/dataset-items")) continue;
                try {
                    data.addObject().put("id", JSON.readTree(line.substring(line.indexOf(' ') + 1)).path("id").asText());
                } catch (Exception ignored) {
                    // A malformed post is simply absent from the existing-id list.
                }
            }
            response = JSON.createObjectNode().set("data", data).toString();
        }
        var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
