package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodeCandidateServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode manifest() throws Exception {
        return (ObjectNode) json.readTree("""
                {"apiVersion":"keel/v1","metadata":{"name":"meta-agent"},"spec":{
                "runtime":{"type":"code","endpoint":"http://meta-agent:8000"},
                "models":{"default":"qwen3.8-flash","budget":{"dailyCny":50}},
                "tools":[],"guardrails":{"inputInjection":"enforce"},
                "eval":{"dataset":"meta-agent/v1","gate":{"minScore":0.99,"maxRegression":0.02}},
                "prompts":{"source":"langfuse","items":[{"name":"draft","type":"text"}]}}}
                """);
    }

    @Test void logicalModelAndVersionedPromptsCanChangeWithoutChangingAuthority() throws Exception {
        var old = manifest();
        var next = old.deepCopy();
        next.withObject("/spec").put("kind", "workflow");
        next.withObject("/spec/models").put("default", "chat-default");
        next.withObject("/spec/eval").put("dataset", "meta-agent/v2");
        next.withObject("/spec/prompts").withArray("items").addObject().put("name", "generate").put("type", "text");
        assertThatCode(() -> CodeCandidateService.requireCompatible(old, next)).doesNotThrowAnyException();
    }

    @Test void candidateCannotReduceGateOrChangeBudgetEndpointOrPermissions() throws Exception {
        var old = manifest();
        for (var path : java.util.List.of("/spec/eval/gate", "/spec/models/budget", "/spec/runtime", "/spec/guardrails")) {
            var next = old.deepCopy();
            next.withObject(path).put("unexpected", true);
            assertThatThrownBy(() -> CodeCandidateService.requireCompatible(old, next)).isInstanceOf(com.keel.server.common.KeelException.class);
        }
        var next = old.deepCopy();
        next.withObject("/spec").withArray("tools").addObject().put("name", "git.pr.merge");
        assertThatThrownBy(() -> CodeCandidateService.requireCompatible(old, next)).isInstanceOf(com.keel.server.common.KeelException.class);
    }

    @Test void fallbackRemovalWithSameDefaultIsAllowedButAddingOrReplacingFallbackIsRejected() throws Exception {
        var old=manifest();old.withObject("/spec/models").put("default","chat-default");
        old.withObject("/spec/models").putArray("fallback").add("qwen3.8-flash").add("deepseek-flash");
        var next=old.deepCopy();next.withObject("/spec/models").remove("fallback");
        assertThatCode(()->CodeCandidateService.requireCompatible(old,next)).doesNotThrowAnyException();
        var widened=next.deepCopy();widened.withObject("/spec/models").putArray("fallback").add("new-model");
        assertThatThrownBy(()->CodeCandidateService.requireCompatible(next,widened)).isInstanceOf(com.keel.server.common.KeelException.class);
        assertThatThrownBy(()->CodeCandidateService.requireCompatible(old,widened)).isInstanceOf(com.keel.server.common.KeelException.class);
        next.withObject("/spec/models/budget").put("dailyCny",100);
        assertThatThrownBy(()->CodeCandidateService.requireCompatible(old,next)).isInstanceOf(com.keel.server.common.KeelException.class);
    }

    @Test void duplicatePromptAndInvalidSkillVersionsAreRejected() throws Exception {
        var old = manifest();
        var next = old.deepCopy();
        next.withObject("/spec/prompts").withArray("items").addObject().put("name", "draft").put("type", "text");
        assertThatThrownBy(() -> CodeCandidateService.requireCompatible(old, next)).isInstanceOf(com.keel.server.common.KeelException.class);
        var skill = old.deepCopy();
        skill.withObject("/spec").withArray("skills").addObject().put("name", "development").put("version", 0);
        assertThatThrownBy(() -> CodeCandidateService.requireCompatible(old, skill)).isInstanceOf(com.keel.server.common.KeelException.class);
    }
}
