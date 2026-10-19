package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.server.auth.ConsolePrincipal;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class CodeRuntimeReconciliationTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode old() throws Exception {
        return (ObjectNode) json.readTree("""
                {"metadata":{"name":"meta-agent"},"spec":{"runtime":{"type":"code"},
                "tools":[{"name":"catalog.lookup","risk":"low","access":"read"}],
                "audit":{"captureFields":["target_agent"]},"eval":{"dataset":"old"},
                "prompts":{"source":"langfuse","items":[{"name":"draft","type":"text"}]}}}
                """);
    }
    private ObjectNode next() throws Exception {
        var next=old();
        next.withObject("/spec").withArray("tools").addObject().put("name","scaffold.deliver").put("risk","mid").put("access","write");
        next.withObject("/spec/audit").withArray("captureFields").add("job_id");
        next.withObject("/spec/eval/gate").put("minScore",.99).put("maxRegression",.02);
        return next;
    }
    @Test void explicitAdminRequired() {
        assertThatCode(() -> CodeCandidateService.requireReconciliationAdmin(ConsolePrincipal.dev())).doesNotThrowAnyException();
        for(var actor : java.util.List.of(ConsolePrincipal.preview(),ConsolePrincipal.service("meta-agent"),
                new ConsolePrincipal("USER","a","a","a","org","DEVELOPER",java.util.List.of(),false))) {
            assertThatThrownBy(() -> CodeCandidateService.requireReconciliationAdmin(actor)).isInstanceOf(com.keel.server.common.KeelException.class);
        }
    }
    @Test void alignsOnlyObservedLocalToolsAuditAndStricterGate() throws Exception {
        var candidate=next();var actual=next();
        ((ObjectNode)actual.at("/spec/tools/0")).putNull("approval");
        var aligned=CodeRuntimeReconciliation.align(old(),candidate,actual,Set.of());
        assertThatCode(() -> CodeCandidateService.requireCompatible(aligned,candidate)).doesNotThrowAnyException();
        assertThat(old().at("/spec/tools").size()).isEqualTo(1);
    }
    @Test void rejectsUnobservedOrForeignToolsAndChangedPermissions() throws Exception {
        assertThatThrownBy(() -> CodeRuntimeReconciliation.align(old(),next(),old(),Set.of())).isInstanceOf(com.keel.server.common.KeelException.class);
        assertThatThrownBy(() -> CodeRuntimeReconciliation.align(old(),next(),next(),Set.of("scaffold.deliver"))).isInstanceOf(com.keel.server.common.KeelException.class);
        var candidate=next();((ObjectNode)candidate.at("/spec/tools/0")).put("access","write");
        assertThatThrownBy(() -> CodeRuntimeReconciliation.align(old(),candidate,candidate,Set.of())).isInstanceOf(com.keel.server.common.KeelException.class);
    }
    @Test void rejectsRuntimeIdentityMismatchAndAuditRemoval() throws Exception {
        var actual=next();actual.withObject("/metadata").put("name","other-agent");
        assertThatThrownBy(() -> CodeRuntimeReconciliation.align(old(),next(),actual,Set.of())).isInstanceOf(com.keel.server.common.KeelException.class);
        var candidate=next();candidate.withObject("/spec/audit").putArray("captureFields").add("job_id");
        assertThatThrownBy(() -> CodeRuntimeReconciliation.align(old(),candidate,candidate,Set.of())).isInstanceOf(com.keel.server.common.KeelException.class);
    }
    @Test void cannotWeakenProductionGateOrChangeProtectedBoundary() throws Exception {
        var prior=next();var candidate=next();candidate.withObject("/spec/eval/gate").put("minScore",.98);
        assertThatThrownBy(() -> CodeRuntimeReconciliation.align(prior,candidate,candidate,Set.of())).isInstanceOf(com.keel.server.common.KeelException.class);
        assertThatThrownBy(() -> CodeRuntimeReconciliation.requireGate(prior.at("/spec/eval/gate"),candidate.at("/spec/eval/gate"))).isInstanceOf(com.keel.server.common.KeelException.class);
        var endpoint=next();endpoint.withObject("/spec/runtime").put("endpoint","http://untrusted");
        var aligned=CodeRuntimeReconciliation.align(old(),endpoint,endpoint,Set.of());
        assertThatThrownBy(() -> CodeCandidateService.requireCompatible(aligned,endpoint)).isInstanceOf(com.keel.server.common.KeelException.class);
    }
}
