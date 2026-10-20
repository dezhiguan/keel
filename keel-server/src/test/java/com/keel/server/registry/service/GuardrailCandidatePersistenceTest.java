package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class GuardrailCandidatePersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test @Transactional void saveCreatesCandidateAndPreservesPublishedManifestImageAndGate() throws Exception {
        var request = new MockHttpServletRequest();
        ConsolePrincipal.set(request, ConsolePrincipal.dev());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        jdbc.update("""
                INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness)
                VALUES ('qa-guard-candidate','QA','AGENT','code','python','QA','dev','ONLINE','k8s')
                """);
        var original = "{\"spec\":{\"guardrails\":{\"canary\":\"off\"}}}";
        jdbc.update("""
                INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,image,gate_run_id,released_by)
                VALUES ('qa-guard-candidate','v2','dev',?::jsonb,'old-hash','image:v2','ev_v2','dev')
                """, original);
        var service = new GuardrailService(jdbc, json, null, "") {
            @Override public java.util.Map<String,Object> page(String name) { return java.util.Map.of(); }
        };
        service.save("qa-guard-candidate", json.readTree("{\"canary\":\"enforce\"}"));
        var snapshot = jdbc.queryForMap("SELECT manifest_json::text AS manifest,image,gate_run_id,manifest_hash FROM agent_version WHERE agent_name='qa-guard-candidate' AND version='v2'");
        assertThat(json.readTree((String)snapshot.get("manifest"))).isEqualTo(json.readTree(original));
        assertThat(snapshot).containsEntry("image","image:v2").containsEntry("gate_run_id","ev_v2").containsEntry("manifest_hash","old-hash");
        var candidate = jdbc.queryForMap("SELECT manifest_json::text AS manifest,image,gate_run_id,released_by,released_at FROM agent_version WHERE agent_name='qa-guard-candidate' AND version='v0'");
        assertThat(json.readTree((String)candidate.get("manifest")).at("/spec/guardrails/canary").asText()).isEqualTo("enforce");
        assertThat(candidate).containsEntry("image",null).containsEntry("gate_run_id",null).containsEntry("released_by","dev");
        service.save("qa-guard-candidate", json.readTree("{\"canary\":\"enforce\"}"));
        assertThat(jdbc.queryForObject("SELECT released_at FROM agent_version WHERE agent_name='qa-guard-candidate' AND version='v0'", java.sql.Timestamp.class)).isEqualTo(candidate.get("released_at"));
    }
}
