package com.keel.server.memory;

import com.keel.server.auth.ConsolePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class MemoryFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM user_memory WHERE agent_name IN ('careermate', 'meta-agent')");
        jdbc.update("DELETE FROM console_audit_event WHERE agent IN ('careermate', 'meta-agent')");
        jdbc.update("DELETE FROM agent WHERE name IN ('careermate', 'meta-agent')");
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness, layer)
                VALUES ('careermate', '求职', 'AGENT', 'code', 'python', '平台', 'dev', 'ONLINE', 'k8s', 'BIZ')
                """);
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness, layer)
                VALUES ('meta-agent', '元智能体', 'AGENT', 'code', 'python', '平台', 'dev', 'ONLINE', 'k8s', 'META')
                """);
    }

    @Test void deleteRemovesTheMemoryAndMetaCannotWrite() throws Exception {
        var saved = mvc.perform(post("/api/v1/agents/careermate/memories").contentType("application/json")
                        .content("{\"ownerUser\":\"u_1\",\"content\":\"偏好上海\"}")
                        .with(asDev()))
                .andExpect(status().isOk())
                .andReturn();
        var id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(saved.getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(get("/api/v1/agents/careermate/memories").param("ownerUser", "u_1"))
                .andExpect(jsonPath("$.data.items[0].content").value("偏好上海"));
        mvc.perform(delete("/api/v1/agents/careermate/memories/" + id).with(asDev()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/agents/careermate/memories").param("ownerUser", "u_1"))
                .andExpect(jsonPath("$.data.items.length()").value(0));
        var payload = jdbc.queryForObject(
                "SELECT payload::text FROM console_audit_event WHERE agent = 'careermate' AND action = 'memory.delete'",
                String.class);
        assertThat(payload).contains("USER").doesNotContain("偏好上海");
        mvc.perform(post("/api/v1/agents/meta-agent/memories").contentType("application/json")
                        .content("{\"ownerUser\":\"u_1\",\"content\":\"不该写\"}")
                        .with(asDev()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMORY_WRITE_DENIED"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor asDev() {
        return request -> {
            ConsolePrincipal.set(request, ConsolePrincipal.dev());
            return request;
        };
    }
}
