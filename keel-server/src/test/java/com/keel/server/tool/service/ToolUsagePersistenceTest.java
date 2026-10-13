package com.keel.server.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker=true)
class ToolUsagePersistenceTest {
    @Autowired ToolRegistryService tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test @Transactional void countsActualAllowedCallsByWindowAgentAndEnvironment() throws Exception {
        tools.register(json.readTree("{\"name\":\"qa.stat.diff\",\"scope\":\"SHARED\",\"access\":\"READ\",\"risk\":\"LOW\",\"provider\":\"git-ci\"}"));
        jdbc.update("INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness) VALUES ('qa-tool-usage','QA','AGENT','code','python','QA','qa','REGISTERED','k8s')");
        for(var env:List.of("dev","test")) jdbc.update("""
            INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,released_by)
            VALUES ('qa-tool-usage','v0',?, '{"spec":{"tools":[{"name":"qa.stat.diff","shared":true,"access":"read","risk":"low"}]}}','hash','qa')
            """,env);
        jdbc.update("""
            INSERT INTO console_audit_event(event_id,agent,env,ts,action,risk,decision,resource,prev_hash,hash)
            VALUES ('u1','qa-tool-usage','dev',now(),'tool.call','LOW','allowed','qa.stat.diff','p','h'),
                   ('u2','qa-tool-usage','test',now(),'tool.call','LOW','allowed','qa.stat.diff','p','h'),
                   ('u3','qa-tool-usage','dev',now()-interval '2 days','tool.call','LOW','allowed','qa.stat.diff','p','h'),
                   ('u4','qa-tool-usage','dev',now(),'tool.call','LOW','denied','qa.stat.diff','p','h'),
                   ('u5','qa-tool-usage','dev',now()-interval '31 days','tool.call','LOW','allowed','qa.stat.diff','p','h')
            """);
        var detail=tools.detail("qa.stat.diff");
        assertThat(detail.get("calls24h")).isEqualTo(2L);
        var dependents=(List<Map<String,Object>>)detail.get("dependents");
        assertThat(dependents).anySatisfy(d->{assertThat(d.get("env")).isEqualTo("dev");assertThat(d.get("calls30d")).isEqualTo(2L);});
        assertThat(dependents).anySatisfy(d->{assertThat(d.get("env")).isEqualTo("test");assertThat(d.get("calls30d")).isEqualTo(1L);});
        assertThat(tools.page("all",null,null,"dev",1,100).items()).anySatisfy(t->{
            assertThat(t.get("name")).isEqualTo("qa.stat.diff");assertThat(t.get("calls24h")).isEqualTo(1L);
        });
    }
}
