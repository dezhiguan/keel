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
@AutoConfigureMockMvc(addFilters = false)
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class SessionHistoryFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.keel.server.registry.service.DebugSessionService sessions;
    @BeforeEach void reset() {
        for(var table:java.util.List.of("session_message","agent_session","session_summary"))
            jdbc.update("DELETE FROM "+table+" WHERE agent_name='history-agent'");
        jdbc.update("DELETE FROM agent WHERE name='history-agent'");
        jdbc.update("INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness,layer) "
            +"VALUES ('history-agent','历史检索测试','AGENT','code','python','org','u_1','ONLINE','k8s','BIZ')");
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor asOwner(String owner) {
        return request->{ConsolePrincipal.set(request,new ConsolePrincipal("USER",owner,owner,owner,"org","USER",java.util.List.of(),false));return request;};
    }
    private static String scoped(String owner,String session) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((owner+":"+session).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void searchesBeyondWindowAndReadsUnicodeOriginalWithScopeAndExpiry() throws Exception {
        var session=(String)sessions.create("history-agent","staging","u_1").get("id");
        var full="填".repeat(25000)+"🦉 IRIS-7419 原文结尾";
        sessions.appendTurn("history-agent","u_1",session,full,"测试原文配对","fixture");
        var first=sessions.history("history-agent","u_1",session).getFirst().get("id");
        for(int i=0;i<70;i++) sessions.appendTurn("history-agent","u_1",session,"近期占位"+i,"近期答复","fixture");
        var recent=sessions.history("history-agent","u_1",session);
        assertThat(recent).hasSize(128);assertThat(recent).noneMatch(r->r.get("content").contains("IRIS-7419"));
        var hash=scoped("u_1",session);var path="/api/v1/agents/history-agent/memories/session-history";
        var body="{\"env\":\"staging\",\"sessionId\":\""+hash+"\",\"query\":\"IRIS-7419\",\"beforeId\":\""+recent.getFirst().get("id")+"\"}";
        mvc.perform(post(path+"/search").with(asOwner("u_1")).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(first))
            .andExpect(jsonPath("$.data.items[0].content").value(org.hamcrest.Matchers.containsString("IRIS-7419")))
            .andExpect(jsonPath("$.data.items[0].totalChars").value(full.codePointCount(0,full.length())));
        mvc.perform(get(path+"/messages/"+first).with(asOwner("u_1")).param("env","staging").param("sessionId",hash)
                .param("offset","25000").param("limitChars","4000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].content").value("🦉 IRIS-7419 原文结尾"))
            .andExpect(jsonPath("$.data.items[0].hasMore").value(false));
        mvc.perform(post(path+"/search").with(asOwner("u_2")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/search").with(asOwner("u_1")).contentType("application/json").content(body.replace("staging","prod"))).andExpect(status().isNotFound());
        var other=(String)sessions.create("history-agent","staging","u_1").get("id");
        mvc.perform(get(path+"/messages/"+first).with(asOwner("u_1")).param("env","staging").param("sessionId",scoped("u_1",other)))
            .andExpect(jsonPath("$.data.items.length()").value(0));
        jdbc.update("UPDATE session_message SET expires_at=now()-interval '1 second' WHERE id=?",Long.parseLong(first));
        mvc.perform(get(path+"/messages/"+first).with(asOwner("u_1")).param("env","staging").param("sessionId",hash))
            .andExpect(jsonPath("$.data.items.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT content FROM session_message WHERE id=?",String.class,Long.parseLong(first))).isEqualTo(full);
    }
    @Test void summaryReferenceReadDetectsVersionChangesAndConcurrentWritersUseCas() throws Exception {
        var session=(String)sessions.create("history-agent","staging","u_1").get("id");
        for(int i=0;i<3;i++) sessions.appendTurn("history-agent","u_1",session,"测试事实"+i,"测试答复"+i,"fixture");
        var history=sessions.history("history-agent","u_1",session);var first=history.getFirst().get("id");
        var hash=scoped("u_1",session);var summary="/api/v1/agents/history-agent/memories/session-summary";
        var prefix="{\"ownerUser\":\"u_1\",\"env\":\"staging\",\"sessionId\":\""+hash+"\",";
        mvc.perform(post(summary).with(asOwner("u_1")).contentType("application/json")
            .content(prefix+"\"version\":0,\"throughId\":\""+first+"\",\"content\":\"事实 [m:"+first+"]\"}"))
            .andExpect(jsonPath("$.data.sourceIds[0]").value(first));
        var ref="/api/v1/agents/history-agent/memories/session-history/messages/"+first;
        mvc.perform(get(ref).with(asOwner("u_1")).param("env","staging").param("sessionId",hash).param("ownerUser","u_1").param("summaryVersion","1"))
            .andExpect(jsonPath("$.data.items[0].id").value(first));
        var futures=new java.util.ArrayList<java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>>();
        for(int index:java.util.List.of(2,4)) futures.add(java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try {
                var id=history.get(index).get("id");
                var result=mvc.perform(post(summary).with(asOwner("u_1")).contentType("application/json")
                    .content(prefix+"\"version\":1,\"throughId\":\""+id+"\",\"content\":\"事实 [m:"+id+"]\"}"))
                    .andExpect(status().isOk()).andReturn();
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).path("data");
            } catch(Exception error) {throw new RuntimeException(error);}
        }));
        var results=futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        assertThat(results.stream().filter(r->r.path("saved").asBoolean()).count()).isEqualTo(1);
        assertThat(results).allMatch(r->r.path("version").asInt()==2);
        assertThat(results.getFirst().path("content")).isEqualTo(results.getLast().path("content"));
        mvc.perform(get(ref).with(asOwner("u_1")).param("env","staging").param("sessionId",hash).param("ownerUser","u_1").param("summaryVersion","1"))
            .andExpect(jsonPath("$.data.stale").value(true)).andExpect(jsonPath("$.data.summary.version").value(2))
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }
}
