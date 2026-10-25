package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.registry.service.DebugSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="KEEL_MEMORY_ASYNC_AGENTS=async-agent:staging")
@AutoConfigureMockMvc(addFilters=false)
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker=true)
class SessionMemoryJobsFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DebugSessionService sessions;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    String session;
    final String root="/api/v1/agents/async-agent/memories/maintenance";
    @BeforeEach void reset(){
        for(var table:List.of("session_memory_attempt","session_memory_job","session_message","agent_session","session_summary","session_fact_state"))jdbc.update("DELETE FROM "+table+" WHERE agent_name='async-agent'");
        jdbc.update("DELETE FROM agent WHERE name='async-agent'");
        jdbc.update("INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness,layer) VALUES ('async-agent','异步测试','AGENT','code','python','org','u_1','ONLINE','k8s','BIZ')");
        session=(String)sessions.create("async-agent","staging","u_1").get("id");
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor identity(String mode,String id){
        return r->{ConsolePrincipal.set(r,mode.equals("SERVICE")?ConsolePrincipal.service(id):new ConsolePrincipal("USER",id,id,id,"org","USER",List.of(),false));return r;};
    }
    JsonNode claim() throws Exception {
        var r=mvc.perform(post(root+"/claim").with(identity("SERVICE","async-agent")).contentType("application/json").content("{\"env\":\"staging\"}"))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("job");
    }
    com.fasterxml.jackson.databind.node.ObjectNode completion(JsonNode job){
        var state=json.createObjectNode().put("version",job.path("expectedVersion").asLong()).put("throughId",job.path("targetId").asText()).put("content","历史事实 [m:"+job.path("messages").get(0).path("id").asText()+"]");
        var body=json.createObjectNode().put("env","staging").put("id",job.path("id").asLong()).put("leaseToken",job.path("leaseToken").asText()).put("calls",1).put("costUpperBoundCny",.1).put("traceId","fixture-background");
        body.set("state",state);return body;
    }
    void enqueueSummary(){
        sessions.appendTurn("async-agent","u_1",session,"检查日期11月26日","收到","fixture");
        long through=Long.parseLong(sessions.history("async-agent","u_1",session).getLast().get("id"));
        sessions.appendTurn("async-agent","u_1",session,"继续","收到","fixture",through);
        jdbc.update("UPDATE session_memory_job SET status='done' WHERE kind='facts' AND agent_name='async-agent'");
    }
    @Test void outboxAndTurnAreAtomicAndDuplicateTurnsCoalesce(){
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            sessions.appendTurn("async-agent","u_1",session,"预算20万","收到","fixture");throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_memory_job WHERE agent_name='async-agent'",Integer.class)).isZero();
        assertThat(sessions.history("async-agent","u_1",session)).isEmpty();
        for(int i=0;i<10;i++)sessions.appendTurn("async-agent","u_1",session,"第"+i+"轮","收到","fixture");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_memory_job WHERE agent_name='async-agent' AND kind='facts'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT desired_id FROM session_memory_job WHERE agent_name='async-agent'",Long.class)).isEqualTo(Long.parseLong(sessions.history("async-agent","u_1",session).get(18).get("id")));
    }
    @Test void leaseRecoveryFencesStaleWriterAndDeletionCannotResurrect() throws Exception {
        enqueueSummary();var abandoned=claim();assertThat(abandoned.path("kind").asText()).isEqualTo("summary");
        assertThat(claim().isNull()).isTrue();
        jdbc.update("UPDATE session_memory_job SET lease_until=now()-interval '1 second' WHERE id=?",abandoned.path("id").asLong());
        var recovered=claim();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_memory_attempt WHERE agent_name='async-agent' AND status='abandoned'",Integer.class)).isEqualTo(1);
assertThat(recovered.path("leaseToken")).isNotEqualTo(abandoned.path("leaseToken"));
        mvc.perform(post(root+"/complete").with(identity("SERVICE","async-agent")).contentType("application/json").content(completion(abandoned).toString())).andExpect(status().isForbidden());
        mvc.perform(post(root+"/complete").with(identity("SERVICE","async-agent")).contentType("application/json").content(completion(recovered).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        assertThat(jdbc.queryForObject("SELECT version FROM session_summary WHERE agent_name='async-agent'",Long.class)).isEqualTo(1);
        sessions.delete("async-agent","u_1",session);
        mvc.perform(post(root+"/complete").with(identity("SERVICE","async-agent")).contentType("application/json").content(completion(recovered).toString())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_summary WHERE agent_name='async-agent'",Integer.class)).isZero();
    }
    @Test void arrivingTurnsDoNotExpandActiveFactsSnapshot() throws Exception {
        sessions.appendTurn("async-agent","u_1",session,"首次问候","收到","fixture");
        var job=claim();var state=json.createObjectNode().put("version",0).put("cursorId",job.path("nextCursor").asText());state.putArray("facts");state.putArray("gaps");
        sessions.appendTurn("async-agent","u_1",session,"新轮问候","收到","fixture");
        var body=completion(job);body.set("state",state);
        mvc.perform(post(root+"/complete").with(identity("SERVICE","async-agent")).contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true)).andExpect(jsonPath("$.data.more").value(true));
        var next=claim();assertThat(next.path("state").path("version").asLong()).isEqualTo(1);
        assertThat(next.path("messages").size()).isEqualTo(1);
    }
    @Test void serviceIdentityOwnerAndEnvironmentAreIsolatedAndRetryIsFinite() throws Exception {
        enqueueSummary();
        for(var as:List.of(identity("USER","u_1"),identity("SERVICE","other-agent")))
            mvc.perform(post(root+"/claim").with(as).contentType("application/json").content("{\"env\":\"staging\"}")).andExpect(status().isForbidden());
        mvc.perform(post(root+"/claim").with(identity("SERVICE","async-agent")).contentType("application/json").content("{\"env\":\"prod\"}")).andExpect(status().isForbidden());
        for(int i=0;i<3;i++){
            var job=claim();var body=completion(job).put("reason","timeout");
            mvc.perform(post(root+"/fail").with(identity("SERVICE","async-agent")).contentType("application/json").content(body.toString())).andExpect(status().isOk());
            jdbc.update("UPDATE session_memory_job SET next_attempt_at=now()-interval '1 second' WHERE id=?",job.path("id").asLong());
        }
        assertThat(claim().isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM session_memory_job WHERE agent_name='async-agent' AND kind='summary'",String.class)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_summary WHERE agent_name='async-agent'",Integer.class)).isZero();
    }
    @Test void expiredTargetDoesNotAdvanceCursor() throws Exception {
        enqueueSummary();var job=claim();
        jdbc.update("UPDATE session_message SET expires_at=now()-interval '1 second' WHERE session_id=?",session);
        mvc.perform(post(root+"/complete").with(identity("SERVICE","async-agent")).contentType("application/json").content(completion(job).toString())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_summary WHERE agent_name='async-agent'",Integer.class)).isZero();
    }
    @Test void consoleSummaryCannotBeReadByAnotherOwnerEvenInSameOrganization() throws Exception {
        enqueueSummary();var job=claim();
        mvc.perform(post(root+"/complete").with(identity("SERVICE","async-agent")).contentType("application/json").content(completion(job).toString())).andExpect(status().isOk());
        var path="/api/v1/agents/async-agent/memories/session-summary";
        mvc.perform(get(path).with(identity("USER","u_2")).param("env","staging").param("sessionId",job.path("sessionId").asText()).param("ownerUser","u_1")).andExpect(status().isForbidden());
        mvc.perform(get(path).with(identity("USER","u_1")).param("env","staging").param("sessionId",job.path("sessionId").asText()).param("ownerUser","display-label"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
    }

}
