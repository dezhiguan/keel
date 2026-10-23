package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceBillingTest {
    @Test void cancelledSummaryUsageAndMoneyAreRecoveredFromTheSameLedger() {
        var answer=generation("r1");
        var summary=generation(""); summary.put("status","failed");
        var detail=new LinkedHashMap<String,Object>();
        detail.put("nodes",List.of(answer,summary)); detail.put("summary",Map.of("tokens",20));
        TraceQueryService.attachBills(detail,List.of(bill("r1","a1","qwen3.8-flash",.003509,"SETTLED"),
                bill("r2","a2","qwen3.8-flash",.006525,"SETTLED")));
        assertThat(summary.get("llmRequestId")).isEqualTo("r2");
        assertThat(summary.get("status")).isEqualTo("failed");
        assertThat(summary.get("tokens")).isEqualTo(20);
        assertThat(((Map<?,?>)detail.get("summary")).get("tokens")).isEqualTo(40L);
        assertThat(((Number)((Map<?,?>)detail.get("summary")).get("costCny")).doubleValue()).isCloseTo(.010034,org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test void successfulRootKeepsRecoveredMaintenanceFailureVisibleWithoutFailingTheTurn() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var root=json.readTree("""
                {"id":"root","name":"agent.run","startTime":"2026-10-23T00:00:00Z","endTime":"2026-10-23T00:00:30Z",
                "metadata":{"keel.agent":"qa","keel.status":"ok","langfuse.observation.type":"agent"}}
                """);
        var child=json.readTree("""
                {"id":"summary","parentObservationId":"root","name":"llm.chat","startTime":"2026-10-23T00:00:01Z","endTime":"2026-10-23T00:00:26Z",
                "metadata":{"keel.agent":"qa","keel.status":"failed","langfuse.observation.type":"generation"},"output":"调用已取消"}
                """);
        var detail=TraceAssembly.detail("trace",List.of(root,child),"",Map.of());
        assertThat(((Map<?,?>)detail.get("summary")).get("status")).isEqualTo("ok");
        assertThat((List<Map<String,Object>>)detail.get("nodes")).anySatisfy(node->{
            assertThat(node.get("id")).isEqualTo("summary"); assertThat(node.get("status")).isEqualTo("failed");
        });
    }
    @Test void inputGuardRejectionWithoutAnyGenerationHasKnownZeroModelCost() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.readTree("""
                {"id":"root","traceId":"trace","name":"agent.run","startTime":"2026-10-19T00:00:00Z","endTime":"2026-10-19T00:00:00.010Z",
                "metadata":{"keel.agent":"qa","keel.status":"failed","keel.blocked":true,"langfuse.observation.type":"agent"}}
                """);
        var guard = json.readTree("""
                {"id":"guard","parentObservationId":"root","traceId":"trace","name":"guard.input","startTime":"2026-10-19T00:00:00Z","endTime":"2026-10-19T00:00:00.001Z",
                "metadata":{"keel.agent":"qa","keel.status":"failed","langfuse.observation.type":"guardrail"}}
                """);
        var detail = TraceAssembly.detail("trace", List.of(root, guard), "", Map.of());
        var summary = (Map<?, ?>) detail.get("summary");
        assertThat(summary.get("costCny")).isEqualTo(0d);
        assertThat(summary.get("blocked")).isEqualTo(true);
    }

    private static LiteLlmClient.Spend bill(String request, String attempt, String model, Double cost, String status) {
        return new LiteLlmClient.Spend("qa-dev", model, cost, "2026-10-19T00:00:00Z", 1L, false,
                request, 10, 10, 0, status, attempt, "trace", "p1", "primary", 0);
    }

    private static Map<String, Object> generation(String id) {
        return new LinkedHashMap<>(Map.of("type", "generation", "model", "chat-default", "agentKey", "qa", "llmRequestId", id));
    }

    @Test void identicalLogicalModelsAreLinkedByRequestIdAndFallbackAttemptsStayTogether() {
        var first = generation("r1");
        var second = generation("r2");
        var detail = new LinkedHashMap<String, Object>();
        detail.put("nodes", List.of(first, second));
        detail.put("summary", Map.of("costCny", 999));
        TraceQueryService.attachBills(detail, List.of(
                bill("r1", "a1", "qwen3.8-flash", 0d, "FAILED"),
                bill("r1", "a2", "deepseek-flash", .002, "SETTLED"),
                bill("r2", "a3", "qwen3.8-flash", .003, "SETTLED")));
        assertThat((List<?>) first.get("attempts")).hasSize(2);
        assertThat((List<?>) second.get("attempts")).hasSize(1);
        assertThat(first.get("costCny")).isEqualTo(.002);
        assertThat(second.get("costCny")).isEqualTo(.003);
        assertThat(((Map<?, ?>) detail.get("summary")).get("costCny")).isEqualTo(.005);
        assertThat((List<?>) detail.get("costBreakdown")).hasSize(1);
    }

    @Test void pendingUsageIsNotReportedAsZeroCost() {
        var node = generation("r1");
        var detail = new LinkedHashMap<String, Object>();
        detail.put("nodes", List.of(node));
        detail.put("summary", Map.of("costCny", 999));
        TraceQueryService.attachBills(detail, List.of(bill("r1", "a1", "qwen3.8-flash", null, "PENDING")));
        assertThat(node.get("costCny")).isNull();
        assertThat(((Map<?, ?>) detail.get("summary")).containsKey("costCny")).isFalse();
        assertThat(((Map<?, ?>) detail.get("summary")).get("pendingBillCount")).isEqualTo(1L);
    }

    @Test void legacyMultipleGenerationsDoNotReceiveDuplicateBills() {
        var first = generation("r1"); first.remove("llmRequestId");
        var second = generation("r2"); second.remove("llmRequestId");
        var detail = new LinkedHashMap<String, Object>();
        detail.put("nodes", List.of(first, second));
        detail.put("summary", Map.of());
        TraceQueryService.attachBills(detail, List.of(bill("r1", "a1", "qwen3.8-flash", .002, "SETTLED")));
        assertThat(first).doesNotContainKey("attempts");
        assertThat(second).doesNotContainKey("attempts");
        assertThat(((Map<?, ?>) detail.get("summary")).get("costCny")).isEqualTo(.002);
    }
}
