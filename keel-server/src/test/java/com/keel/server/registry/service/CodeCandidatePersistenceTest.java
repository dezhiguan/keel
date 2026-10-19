package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.server.PostgresTestConfig;
import com.keel.server.auth.ConsolePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker=true)
class CodeCandidatePersistenceTest {
    @Autowired CodeCandidateService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @AfterEach void clearActor() { RequestContextHolder.resetRequestAttributes(); }

    @Test @Transactional void savesDatabaseCompatibleHashAndPreservesReleasedSnapshot() throws Exception {
        var request=new MockHttpServletRequest();
        ConsolePrincipal.set(request,ConsolePrincipal.dev());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var original=json.readTree("""
                {"apiVersion":"keel/v1","kind":"Agent","metadata":{"name":"qa-candidate-persistence","owner":"QA / dev"},
                "spec":{"runtime":{"type":"code","endpoint":"http://qa-candidate:8000"},
                "models":{"default":"qwen3.8-flash","budget":{"dailyCny":50}},"tools":[],
                "prompts":{"source":"langfuse","items":[{"name":"draft","type":"text"}]},
                "eval":{"dataset":"qa-candidate/v1","gate":{"minScore":0.99,"maxRegression":0.02}}}}
                """);
        var oldHash="a".repeat(64);
        jdbc.update("INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness) VALUES ('qa-candidate-persistence','QA','AGENT','code','python','QA','dev','ONLINE','k8s')");
        for(var version:java.util.List.of("v0","v1")) jdbc.update("INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,image,gate_run_id,released_by) VALUES ('qa-candidate-persistence',?,'dev',?::jsonb,?,'registry/old:v1','ev_old','dev')",version,original.toString(),oldHash);
        ObjectNode candidate=original.deepCopy();
        candidate.withObject("/spec/models").put("default","chat-default");
        var body=json.createObjectNode().put("env","dev").put("expectedHash",oldHash);
        body.set("manifest",candidate);
        var saved=service.update("qa-candidate-persistence",body);
        assertThat(saved.get("manifestHash")).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("SELECT manifest_hash FROM agent_version WHERE agent_name='qa-candidate-persistence' AND version='v0'",String.class)).isEqualTo(saved.get("manifestHash"));
        assertThat(jdbc.queryForMap("SELECT image,gate_run_id FROM agent_version WHERE agent_name='qa-candidate-persistence' AND version='v0'")).containsEntry("image",null).containsEntry("gate_run_id",null);
        assertThat(jdbc.queryForObject("SELECT manifest_hash FROM agent_version WHERE agent_name='qa-candidate-persistence' AND version='v1'",String.class)).isEqualTo(oldHash);
        var snapshot=json.readTree(jdbc.queryForObject("SELECT manifest_json::text FROM agent_version WHERE agent_name='qa-candidate-persistence' AND version='v1'",String.class));
        assertThat(snapshot).isEqualTo(original);
        body.put("expectedHash",saved.get("manifestHash"));
        assertThat(service.update("qa-candidate-persistence",body)).isEqualTo(saved);
    }
}
