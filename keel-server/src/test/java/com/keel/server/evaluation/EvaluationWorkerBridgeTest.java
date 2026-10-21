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
}
