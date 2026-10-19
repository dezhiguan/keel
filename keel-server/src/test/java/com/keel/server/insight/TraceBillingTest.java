package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceBillingTest {
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
