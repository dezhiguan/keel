package com.keel.server.evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class EvaluationWorkerBridgeTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void serviceAndPreviewCannotBecomeTheEvaluationUser() {
        assertThatThrownBy(()->EvaluationWorkerBridge.requireUser(ConsolePrincipal.service("agent"))).isInstanceOf(KeelException.class);
        assertThatThrownBy(()->EvaluationWorkerBridge.requireUser(ConsolePrincipal.preview())).isInstanceOf(KeelException.class);
        EvaluationWorkerBridge.requireUser(new ConsolePrincipal("USER","54","test","test","平台组","ADMIN",List.of(),false));
        assertThat(EvaluationWorkerBridge.hash("test")).hasSize(64).isNotEqualTo("test");
    }
    @Test void blockedCriticalOrIncompleteCoverageCannotClaimPassing() throws Exception {
        var frozen=List.of(json.readTree("{\"metadata\":{\"caseId\":\"case-a\",\"severity\":\"critical\"}}"));
        var result=json.readTree("""
            {"agent":"agent","candidateVersion":"ev_test","dataset":"suite","runner":"acceptance-v1","caseCount":1,
             "complete":false,"passed":false,"scoreTotal":0,"coverage":{"total":1,"executed":0,"passed":0,"failed":0,"blocked":1,"reviewRequired":0},
             "cases":[{"caseId":"case-a","status":"BLOCKED","executed":false}]}
            """);
        EvaluationWorkerBridge.validateProgress(result,"ev_test","agent","suite",frozen);
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("passed",true);
        assertThatThrownBy(()->EvaluationWorkerBridge.validateProgress(result,"ev_test","agent","suite",frozen)).isInstanceOf(KeelException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("passed",false);
        ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("coverage")).put("executed",1);
        assertThatThrownBy(()->EvaluationWorkerBridge.validateProgress(result,"ev_test","agent","suite",frozen)).isInstanceOf(KeelException.class);
    }
    @Test void serviceFilterOnlyExposesRunScopedCallbackPaths() {
        assertThat(com.keel.server.auth.CatalogAccess.serviceAccessible("POST","/api/v1/eval/runs/ev_"+"a".repeat(32)+"/context")).isTrue();
        assertThat(com.keel.server.auth.CatalogAccess.serviceAccessible("POST","/api/v1/eval/agent/runs")).isFalse();
        assertThat(com.keel.server.auth.CatalogAccess.serviceAccessible("POST","/api/v1/eval/agent/runs/ev_test/publish")).isFalse();
    }
    @Test void aCriticalFailureCannotBeOffsetByTwentyNinePassingCases() {
        var frozen=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var result=json.createObjectNode().put("agent","agent").put("candidateVersion","ev_test").put("dataset","suite")
                .put("runner","acceptance-v1").put("caseCount",30).put("scoreTotal",29.0/30).put("complete",true).put("passed",true);
        var rows=result.putArray("cases");
        for(var i=0;i<30;i++) {
            var source=json.createObjectNode();source.putObject("metadata").put("caseId","case-"+i).put("severity",i==29?"critical":"normal");frozen.add(source);
            rows.addObject().put("caseId","case-"+i).put("status",i==29?"FAIL":"PASS").put("executed",true).put("critical",false);
        }
        result.putObject("coverage").put("total",30).put("executed",30).put("passed",29).put("failed",1).put("blocked",0).put("reviewRequired",0);
        assertThatThrownBy(()->EvaluationWorkerBridge.validateProgress(result,"ev_test","agent","suite",frozen)).isInstanceOf(KeelException.class);
        result.put("passed",false);
        assertThatCode(()->EvaluationWorkerBridge.validateProgress(result,"ev_test","agent","suite",frozen)).doesNotThrowAnyException();
    }
}
