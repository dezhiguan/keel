package com.keel.server.evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.PostgresTestConfig;
import com.keel.server.auth.*;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @Import(PostgresTestConfig.class) @Testcontainers(disabledWithoutDocker=true)
class EvaluationWorkerPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final String id="ev_"+"a".repeat(32),cap="b".repeat(64);
    private EvaluationWorkerBridge bridge;
    private void caller(String agent){var request=new MockHttpServletRequest();ConsolePrincipal.set(request,ConsolePrincipal.service(agent));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    @BeforeEach void setup() throws Exception {
        var user=new ConsolePrincipal("USER","54","tester","测试","平台组","ADMIN",List.of(),false);
        jdbc.update("INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by,runner,context_user,capability_hash) VALUES (?,'assistant','staging','agents','pod','image','http://agent','suite','tester','acceptance-v1',?::jsonb,?)",id,json.writeValueAsString(user),EvaluationWorkerBridge.hash(cap));
        var fixture=json.readTree("[{\"status\":\"ACTIVE\",\"metadata\":{\"caseId\":\"case-a\"},\"input\":{\"text\":\"hello\"},\"expectedOutput\":{}}]");
        var lf=new LangfuseClient("","","") {
            @Override public com.fasterxml.jackson.databind.JsonNode datasetItemsComplete(String dataset){return fixture;}
        };
        bridge=new EvaluationWorkerBridge(jdbc,json,null,new UserReadContext(new ConsoleSigningKey(),new ConsoleUsers("tester")),new ConsoleUsers("tester"),lf);
        caller("assistant");
    }
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    @Test @Transactional void capabilityCannotCrossAgentAndHistoryIsPersistedOutsideUserSessions() throws Exception {
        var body=json.readTree("{\"caseId\":\"case-a\",\"turn\":0}");
        assertThatThrownBy(()->bridge.context(id,"c".repeat(64),body)).isInstanceOf(KeelException.class);
        caller("other");assertThatThrownBy(()->bridge.context(id,cap,body)).isInstanceOf(KeelException.class);caller("assistant");
        var first=bridge.context(id,cap,body);assertThat((List<?>)first.get("history")).isEmpty();
        var policy=(Map<?,?>)first.get("policyEvidence");
        assertThat((List<?>)policy.get("employeeEnvironments")).isEmpty();
        assertThat((List<?>)policy.get("retiredEnvironments")).hasSize(2);
        bridge.saveTurn(id,cap,json.readTree("{\"caseId\":\"case-a\",\"turn\":0,\"question\":\"先查A\",\"answer\":\"A信息\",\"evidence\":{}}"));
        var next=bridge.context(id,cap,json.readTree("{\"caseId\":\"case-a\",\"turn\":1}"));
        assertThat((List<?>)next.get("history")).hasSize(2);
        assertThat((List<?>)bridge.context(id,cap,json.readTree("{\"caseId\":\"case-a\",\"turn\":1,\"sessionGroup\":\"fresh\"}")).get("history")).isEmpty();
        jdbc.update("UPDATE eval_run SET state='DONE' WHERE run_id=?",id);
        assertThatThrownBy(()->bridge.context(id,cap,body)).isInstanceOf(KeelException.class);
    }
    @Test @Transactional void authenticatedProgressMarksActualWorkerRunning() throws Exception {
        var snapshot=bridge.context(id,cap,json.readTree("{\"bootstrap\":true}"));
        var result=json.createObjectNode().put("agent","assistant").put("candidateVersion",id).put("dataset","suite")
                .put("runner","acceptance-v1").put("datasetHash",(String)snapshot.get("datasetHash"))
                .put("caseCount",1).put("scoreTotal",0).put("passed",false).put("complete",false);
        result.putArray("cases").addObject().put("caseId","case-a").put("status","NOT_RUN").put("executed",false);
        result.putObject("coverage").put("total",1).put("executed",0).put("passed",0).put("failed",0).put("blocked",0).put("reviewRequired",0);
        bridge.progress(id,cap,result);
        assertThat(jdbc.queryForObject("SELECT state FROM eval_run WHERE run_id=?",String.class,id)).isEqualTo("RUNNING");
    }
}
