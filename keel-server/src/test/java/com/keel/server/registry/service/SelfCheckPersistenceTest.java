package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.PostgresTestConfig;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.*;
import com.keel.server.registry.model.enums.AgentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker=true)
class SelfCheckPersistenceTest {
    @Autowired SelfCheckRecords records;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test @Transactional void savedProbeSurvivesReadAndDoesNotApplyToAnotherManifest() throws Exception {
        var manifest=json.readTree("{\"apiVersion\":\"keel/v1\",\"spec\":{\"runtime\":{\"language\":\"python\"}}}");
        var agent=new Agent(); agent.setName("qa-check"); agent.setKind("AGENT"); agent.setStatus(AgentStatus.REGISTERED); agent.setLanguage("python");
        var version=new AgentVersion(); version.setEnv("dev"); version.setVersion("v0");
        version.setManifestHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        jdbc.update("INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness) VALUES ('qa-check','QA','AGENT','code','python','QA','qa','REGISTERED','k8s')");
        jdbc.update("INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,released_by) VALUES ('qa-check','v0','dev',?::jsonb,?,'qa')",manifest.toString(),version.getManifestHash());
        var fromDatabase=json.readTree(jdbc.queryForObject("SELECT manifest_json::text FROM agent_version WHERE agent_name='qa-check'",String.class));
        assertThat(fromDatabase).isEqualTo(manifest);
        assertThat(fromDatabase.toString()).isNotEqualTo(manifest.toString());
        var detail=AgentDetail.of(agent,version,null,List.of(),manifest,List.of());
        records.save("qa-check","dev",fromDatabase,new SelfCheckService.Report(true,List.of(new SelfCheckService.Item("协议",true,"trace-qa"))));
        var loaded=records.apply(detail);
        assertThat(loaded.selfCheck().checkedAt()).isNotBlank();
        assertThat(loaded.selfCheck().items()).anySatisfy(i->{assertThat(i.name()).isEqualTo("调用协议自检");assertThat(i.detail()).isEqualTo("trace-qa");});
        version.setManifestHash("changed");
        assertThat(records.apply(AgentDetail.of(agent,version,null,List.of(),manifest,List.of())).selfCheck().checkedAt()).isNull();
    }
}
