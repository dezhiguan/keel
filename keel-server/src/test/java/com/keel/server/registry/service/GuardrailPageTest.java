package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class GuardrailPageTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsRegisteredModesAndCountsOnlyThisAgentsHits() throws Exception {
        var manifest = json.readTree("""
                {"spec":{"guardrails":{"inputInjection":"enforce","pii":"mask","grounding":"off"}}}
                """);
        var observations = List.of(json.readTree("""
                {"traceId":"tr_1","name":"guard.input","startTime":"2026-10-18T01:00:00Z","output":"命中","metadata":{"keel.agent":"careermate","keel.status":"failed","langfuse.observation.type":"guardrail"}}
                """), json.readTree("""
                {"traceId":"tr_2","name":"guard.pii","startTime":"2026-10-18T02:00:00Z","output":"命中手机号","metadata":{"keel.agent":"careermate","keel.status":"ok","langfuse.observation.type":"guardrail"}}
                """), json.readTree("""
                {"traceId":"tr_9","name":"guard.input","startTime":"2026-10-18T03:00:00Z","metadata":{"keel.agent":"other","keel.status":"failed"}}
                """));
        var page = GuardrailPage.view("careermate", "v14", "dev", "careermate-abc", manifest, observations, List.of(),
                List.of(new GuardrailPage.Caller("careermate（走 ctx.llm）", "careermate-dev", "sdk")));
        var checks = (List<?>) page.get("checks");
        assertEquals("enforce", ((java.util.Map<?, ?>) checks.get(0)).get("mode"));
        assertEquals(1, ((java.util.Map<?, ?>) checks.get(0)).get("hits"));
        assertEquals("off", ((java.util.Map<?, ?>) checks.get(1)).get("mode"));
        assertEquals("mask", ((java.util.Map<?, ?>) checks.get(4)).get("mode"));
        assertEquals(1, ((java.util.Map<?, ?>) checks.get(4)).get("hits"));
        assertEquals("tr_2", page.get("traceId"));
        assertFalse(((List<?>) page.get("trace")).isEmpty());
    }

    @Test
    void readsFlattenedCloudOtelAttributesWithoutLosingOwnershipOrFailedStatus() throws Exception {
        var observation = json.readTree("""
                {"traceId":"tr_flat","type":"GUARDRAIL","name":"guard.input","startTime":"2026-10-19T01:00:00Z",
                 "metadata":{"attributes.keel.agent":"qa-guard-1019","attributes.keel.status":"failed",
                             "attributes.langfuse.observation.type":"guardrail"}}
                """);
        var page = GuardrailPage.view("qa-guard-1019", "v0", "dev", "pod", null,
                List.of(observation), List.of(observation), List.of());
        assertEquals("qa-guard-1019", GuardrailPage.agentOf(observation));
        assertEquals(true, GuardrailPage.hit(observation));
        assertEquals("tr_flat", page.get("traceId"));
        assertEquals(1, ((java.util.Map<?, ?>) ((List<?>) page.get("checks")).get(0)).get("hits"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) observation.get("metadata")).put("keel.agent", "direct");
        assertEquals("direct", GuardrailPage.agentOf(observation));
    }

    @Test
    void leavesHitsUnknownWhenLangfuseWasNotRead() throws Exception {
        var page = GuardrailPage.view("careermate", null, null, null, null, null, List.of(), List.of());
        var checks = (List<?>) page.get("checks");
        assertNull(((java.util.Map<?, ?>) checks.get(0)).get("hits"));
        assertEquals("shadow", ((java.util.Map<?, ?>) checks.get(0)).get("mode"));
    }

    @Test
    void storesModesWithoutDroppingOtherGuardrailFields() throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"spec":{"guardrails":{"grounding":"off","inputInjection":"shadow"}}}
                """);
        var body = json.readTree("""
                {"input":"enforce","indirect":"shadow","tainted":"拦截","canary":"enforce","pii":"mask"}
                """);
        GuardrailPage.apply(root, body);
        var guard = root.path("spec").path("guardrails");
        assertEquals("enforce", guard.path("inputInjection").asText());
        assertEquals("off", guard.path("grounding").asText());
        assertEquals("block", guard.path("taintedTool").asText());
        assertEquals("拦截", GuardrailPage.readModes(root).get("tainted"));
    }
}
