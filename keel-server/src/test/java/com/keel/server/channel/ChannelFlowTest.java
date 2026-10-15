package com.keel.server.channel;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ChannelFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM channel_credential WHERE channel_id IN (SELECT id FROM channel WHERE agent_name = 'careermate')");
        jdbc.update("DELETE FROM channel WHERE agent_name = 'careermate'");
        jdbc.update("DELETE FROM agent_version WHERE agent_name = 'careermate'");
        jdbc.update("DELETE FROM agent WHERE name = 'careermate'");
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness, layer)
                VALUES ('careermate', '求职', 'AGENT', 'code', 'python', '平台', 'dev', 'ONLINE', 'k8s', 'BIZ')
                """);
        jdbc.update("""
                INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash, released_by)
                VALUES ('careermate', 'v1', 'dev', ?::jsonb, 'hash', 'test')
                """, "{\"spec\":{\"tools\":[{\"name\":\"resume.update\",\"risk\":\"high\",\"approval\":\"required\"}]}}");
    }

    @Test void unknownOriginIsDeniedAndTheHostedPageSetsNoCookie() throws Exception {
        var saved = mvc.perform(post("/api/v1/agents/careermate/channels").contentType("application/json")
                        .content("{\"kind\":\"EMBED\",\"identity\":\"EXCHANGE\",\"origin\":\"https://hr.example.com\"}")
                        .with(request -> {
                            ConsolePrincipal.set(request, ConsolePrincipal.dev());
                            return request;
                        }))
                .andExpect(status().isOk())
                .andReturn();
        var credential = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(saved.getResponse().getContentAsString()).path("data").path("credential").asText();
        mvc.perform(post("/api/v1/agents/careermate/channels/entry").contentType("application/json")
                        .content("{\"origin\":\"https://evil.example\",\"credential\":\"" + credential + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANNEL_ORIGIN_DENIED"));
        mvc.perform(post("/api/v1/agents/careermate/channels").contentType("application/json")
                        .content("{\"kind\":\"EMBED\",\"identity\":\"ANONYMOUS\",\"origin\":\"https://hr.example.com\"}")
                        .with(request -> {
                            ConsolePrincipal.set(request, ConsolePrincipal.dev());
                            return request;
                        }))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE agent_version SET manifest_json='{" + "\"spec\":{}" + "}'::jsonb WHERE agent_name='careermate'");
        mvc.perform(post("/api/v1/agents/careermate/channels").contentType("application/json")
                        .content("{\"kind\":\"HOSTED\",\"identity\":\"ANONYMOUS\"}")
                        .with(request -> { ConsolePrincipal.set(request, ConsolePrincipal.dev()); return request; }))
                .andExpect(status().isOk());
        mvc.perform(get("/a/careermate").cookie(new jakarta.servlet.http.Cookie("keel_console_at", "secret")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-credential=\"ch_")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("keel_console"))));
    }
}
