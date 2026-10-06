package com.keel.server.insight;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.integration.prometheus.PrometheusExposition;
import com.keel.server.integration.ragforge.RagForgeInsightClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SharedServiceMonitorTest {

    @Test void blankAddressLeavesLatencyNull() {
        var monitor = new SharedServiceMonitor(new RagForgeInsightClient("", "", ""), new LiteLlmClient("", ""));
        @SuppressWarnings("unchecked")
        var service = ((List<Map<String, Object>>) monitor.services().get("services")).get(0);
        assertThat(service.get("p95")).isNull();
        assertThat(service.get("status")).isNull();
    }

    @Test void insightAndStageMeansFillTheSharedServicePage() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> write(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/actuator/keel", exchange -> write(exchange, 200, """
                {"searches24h":0,"searchesPrev24h":4,"p50Ms":null,"p95Ms":null,"zeroResultRate":null,
                 "modelCostCny":0.4,"knowledgeBases":[
                   {"kb":"fault-manual","owner":"电力运维组","documents":12,"updatedAt":"2026-08-01T00:00:00Z",
                    "searches24h":0,"zeroHitRate":null,"recallAt5":null,"stale":true}]}
                """));
        server.createContext("/actuator/prometheus", exchange -> write(exchange, 200, """
                ragforge_retrieval_latency_seconds_sum{stage="vector",strategy="hybrid"} 10.5
                ragforge_retrieval_latency_seconds_count{stage="vector",strategy="hybrid"} 100
                ragforge_retrieval_latency_seconds_sum{stage="total",strategy="hybrid"} 20
                ragforge_retrieval_latency_seconds_count{stage="total",strategy="hybrid"} 100
                ragforge_retrieval_requests_total{caller_agent="askdb",strategy="hybrid"} 4
                ragforge_retrieval_requests_total{strategy="hybrid"} 9
                """));
        server.createContext("/", exchange -> write(exchange, 404, ""));
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var monitor = new SharedServiceMonitor(
                new RagForgeInsightClient(base, "metrics-reader", "secret"),
                new LiteLlmClient(base, "admin"));
        var body = monitor.services();
        @SuppressWarnings("unchecked")
        var service = ((List<Map<String, Object>>) body.get("services")).get(0);
        assertThat(service.get("status")).isEqualTo("ONLINE");
        assertThat(service.get("p95")).isNull();
        @SuppressWarnings("unchecked")
        var rag = (Map<String, Object>) body.get("ragforge");
        @SuppressWarnings("unchecked")
        var kpi = (Map<String, Object>) rag.get("kpi");
        assertThat(kpi.get("searches24h")).isEqualTo(0L);
        assertThat(kpi.get("searchTrendPct")).isEqualTo(-100.0);
        assertThat(kpi.get("kbCount")).isEqualTo(1);
        assertThat(kpi.get("staleKbCount")).isEqualTo(1L);
        assertThat(kpi.get("modelCostCny")).isEqualTo(0.4);
        assertThat(kpi.get("p95Seconds")).isNull();
        @SuppressWarnings("unchecked")
        var stages = (List<Map<String, Object>>) rag.get("stageLatency");
        assertThat(stages).anySatisfy(stage -> {
            assertThat(stage.get("stage")).isEqualTo("vector");
            assertThat(stage.get("meanMs")).isEqualTo(105);
            assertThat(stage.get("basis")).isEqualTo("mean");
        });
        assertThat(stages).noneMatch(stage -> "total".equals(stage.get("stage")));
        @SuppressWarnings("unchecked")
        var callers = (List<Map<String, Object>>) rag.get("callers");
        assertThat(callers).containsExactly(Map.of("agent", "askdb", "calls", 4));
        @SuppressWarnings("unchecked")
        var bases = (List<Map<String, Object>>) rag.get("knowledgeBases");
        assertThat(bases.get(0)).containsEntry("kb", "fault-manual").containsEntry("recallAt5", null);
        server.stop(0);
    }

    @Test void unreachableAddressDoesNotInventHealth() {
        var monitor = new SharedServiceMonitor(
                new RagForgeInsightClient("http://127.0.0.1:1", "metrics-reader", "secret"),
                new LiteLlmClient("", ""));
        @SuppressWarnings("unchecked")
        var service = ((List<Map<String, Object>>) monitor.services().get("services")).get(0);
        assertThat(service.get("p95")).isNull();
        assertThat(service.get("status")).isNull();
        assertThat(PrometheusExposition.parse("# comment\nup 1\n").get(0).value()).isEqualTo(1d);
    }

    @Test void oneEnvironmentDropsCallersFromOtherAgents() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/prometheus", exchange -> write(exchange, 200, """
                ragforge_retrieval_requests_total{caller_agent="askdb",strategy="hybrid"} 4
                ragforge_retrieval_requests_total{caller_agent="wind",strategy="hybrid"} 2
                """));
        server.createContext("/", exchange -> write(exchange, 404, ""));
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var monitor = new SharedServiceMonitor(
                new RagForgeInsightClient(base, "metrics-reader", "secret"),
                new LiteLlmClient("", ""),
                env -> List.of("wind"));
        var body = monitor.services("test");
        @SuppressWarnings("unchecked")
        var rag = (Map<String, Object>) body.get("ragforge");
        @SuppressWarnings("unchecked")
        var callers = (List<Map<String, Object>>) rag.get("callers");
        assertThat(callers).containsExactly(Map.of("agent", "wind", "calls", 2));
        @SuppressWarnings("unchecked")
        var kpi = (Map<String, Object>) rag.get("kpi");
        assertThat(kpi.get("modelCostCny")).isNull();
        server.stop(0);
    }

    @Test void langfuseAndEvalSummaryFillMissingRetrievalFigures() throws Exception {
        var start = java.time.Instant.now().minusSeconds(1);
        var end = java.time.Instant.now();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/keel", exchange -> write(exchange, 200, """
                {"searches24h":0,"searchesPrev24h":0,"p50Ms":null,"p95Ms":null,"knowledgeBases":[
                  {"kb":"fault-manual","owner":"电力运维组","documents":1,"updatedAt":"2026-10-01T00:00:00Z",
                   "searches24h":0,"zeroHitRate":null,"recallAt5":null,"stale":false}]}
                """));
        server.createContext("/api/v1/eval/summary", exchange -> write(exchange, 200,
                "{\"recallAt5\":0.91,\"zeroResultRate\":0.012,\"evaluatedAt\":\"2026-10-01T00:00:00Z\"}"));
        server.createContext("/api/public/v2/observations", exchange -> write(exchange, 200, """
                {"data":[
                  {"type":"RETRIEVER","name":"rag.search","startTime":"%s","endTime":"%s","metadata":{"keel.agent":"askdb"}},
                  {"type":"SPAN","name":"rerank","startTime":"%s","endTime":"%s","metadata":{"keel.agent":"askdb"}}
                ],"meta":{}}
                """.formatted(start, end, start, end)));
        server.createContext("/actuator/prometheus", exchange -> write(exchange, 200, """
                ragforge_retrieval_requests_total{status="429",caller_agent=""} 3
                ragforge_retrieval_requests_total{status="ok"} 97
                """));
        server.createContext("/", exchange -> write(exchange, 404, ""));
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var monitor = new SharedServiceMonitor(
                new RagForgeInsightClient(base, "metrics-reader", "secret"),
                new LiteLlmClient("", ""),
                env -> List.of(),
                new LangfuseClient(base, "pk", "sk"));
        var body = monitor.services("all");
        @SuppressWarnings("unchecked")
        var rag = (Map<String, Object>) body.get("ragforge");
        @SuppressWarnings("unchecked")
        var kpi = (Map<String, Object>) rag.get("kpi");
        assertThat(kpi.get("searches24h")).isEqualTo(1L);
        assertThat(kpi.get("p95Seconds")).isEqualTo(1.0);
        assertThat(kpi.get("throttleRate")).isEqualTo(0.03);
        @SuppressWarnings("unchecked")
        var service = ((List<Map<String, Object>>) body.get("services")).get(0);
        assertThat(service.get("errorRate")).isEqualTo("3.0%");
        @SuppressWarnings("unchecked")
        var callers = (List<Map<String, Object>>) rag.get("callers");
        assertThat(callers).containsExactly(Map.of("agent", "askdb", "calls", 1));
        @SuppressWarnings("unchecked")
        var stages = (List<Map<String, Object>>) rag.get("stageLatency");
        assertThat(stages).anySatisfy(stage -> {
            assertThat(stage.get("stage")).isEqualTo("rerank");
            assertThat(stage.get("basis")).isEqualTo("p50");
        });
        @SuppressWarnings("unchecked")
        var bases = (List<Map<String, Object>>) rag.get("knowledgeBases");
        assertThat(bases.get(0)).containsEntry("recallAt5", 0.91).containsEntry("zeroHitRate", 0.012);
        assertThat(body.get("consoleUrl")).isEqualTo("https://ragforge.net");
        server.stop(0);
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
