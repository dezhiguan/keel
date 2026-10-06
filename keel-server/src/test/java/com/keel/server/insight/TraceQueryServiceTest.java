package com.keel.server.insight;

import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceQueryServiceTest {

    @Test void detailShowsObservationInputAndOutput() throws Exception {
        var paths = new ArrayList<String>();
        var server = server(paths, """
                {"data":[
                  {"id":"n1","traceId":"tr-1","name":"askdb","startTime":"2026-10-04T03:00:00.000Z","endTime":"2026-10-04T03:00:00.100Z","input":"RAW_USER_TEXT_SHOULD_NOT_LEAK","metadata":{"keel.agent":"askdb","langfuse.observation.type":"agent","keel.audit_ids":["evt-1"]}},
                  {"id":"n2","traceId":"tr-1","parentObservationId":"n1","name":"retrieve","startTime":"2026-10-04T03:00:00.010Z","endTime":"2026-10-04T03:00:00.040Z","metadata":{"keel.agent":"rag-forge","langfuse.observation.type":"retriever"}},
                  {"id":"n3","traceId":"tr-1","parentObservationId":"n1","name":"offshore","startTime":"2026-10-04T03:00:00.040Z","endTime":"2026-10-04T03:00:00.100Z","metadata":{"keel.agent":"offshore-wind","langfuse.observation.type":"agent","gen_ai.request.model":"unknown-model","gen_ai.usage.input_tokens":10,"gen_ai.usage.output_tokens":5}}
                ]}
                """);
        var service = new TraceQueryService(client(server), "https://jp.cloud.langfuse.com", "proj-1", Map.of("qwen-plus", new double[] {0.001, 0.002}));
        var detail = service.detail("tr-1");
        assertThat(paths).containsExactly("/api/public/v2/observations");
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) detail.get("nodes");
        @SuppressWarnings("unchecked")
        var edges = (List<Map<String, Object>>) detail.get("edges");
        var ids = nodes.stream().map(node -> node.get("id")).toList();
        assertThat(edges).allSatisfy(edge -> {
            assertThat(ids).contains(edge.get("from"));
            assertThat(ids).contains(edge.get("to"));
        });
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) detail.get("summary");
        @SuppressWarnings("unchecked")
        var breakdown = (List<Map<String, Object>>) detail.get("latencyBreakdown");
        int sum = breakdown.stream().mapToInt(item -> (Integer) item.get("ms")).sum();
        assertThat(sum).isEqualTo(summary.get("durationMs"));
        assertThat(detail.get("langfuseUrl")).isEqualTo("https://jp.cloud.langfuse.com/project/proj-1/traces/tr-1");
        assertThat(nodes).anySatisfy(node -> assertThat(String.valueOf(node.get("auditIds"))).contains("evt-1"));
        assertThat(nodes).anySatisfy(node -> assertThat(node.get("type")).isEqualTo("retriever"));
        assertThat(nodes).anySatisfy(node -> {
            if ("offshore".equals(node.get("name"))) {
                assertThat(node.get("costCny")).isNull();
            }
        });
        assertThat(nodes).anySatisfy(node -> {
            if ("askdb".equals(node.get("name"))) {
                assertThat(node.get("inputSummary")).isEqualTo("RAW_USER_TEXT_SHOULD_NOT_LEAK");
            }
        });
        assertThat(nodes).anySatisfy(node -> {
            if ("retrieve".equals(node.get("name"))) {
                assertThat(node.get("inputSummary")).isEqualTo("retrieve");
            }
        });
        server.stop(0);
    }

    @Test void aMissingLangfuseTraceFallsBackToTheLocalRecord() throws Exception {
        var server = server(new ArrayList<>(), "{\"data\":[]}");
        var service = new TraceQueryService(client(server), "https://jp.cloud.langfuse.com", "proj-1", Map.of(), new SavedTraces() {
            @Override
            public void save(String agent, String env, String traceId, String question, int durationMs) {
            }

            @Override
            public Map<String, Object> list(int page, int size, String agent) {
                return Map.of();
            }

            @Override
            public Map<String, Object> detail(String traceId) {
                return "tr-suspended".equals(traceId) ? Map.of("summary", Map.of("traceId", traceId)) : null;
            }
        });
        assertThat(service.detail("tr-suspended").get("summary")).isEqualTo(Map.of("traceId", "tr-suspended"));
        server.stop(0);
    }

    @Test void unknownTraceIsNotFound() throws Exception {
        var paths = new ArrayList<String>();
        var server = server(paths, "{\"data\":[]}");
        var service = new TraceQueryService(client(server), "https://jp.cloud.langfuse.com", "proj-1", Map.of());
        assertThatThrownBy(() -> service.detail("missing"))
                .isInstanceOf(KeelException.class);
        assertThat(paths).allMatch(path -> path.startsWith("/api/public/v2/observations"));
        server.stop(0);
    }

    @Test void listUsesSavedTracesWhenLangfuseIsNotConfigured() {
        var service = new TraceQueryService(new LangfuseClient("", "", ""), "", "", Map.of(), new SavedTraces() {
            @Override
            public void save(String agent, String env, String traceId, String question, int durationMs) {
            }

            @Override
            public Map<String, Object> list(int page, int size, String agent) {
                return Map.of("page", page, "size", size, "total", 1, "items", List.of(Map.of("traceId", "local-1")));
            }

            @Override
            public Map<String, Object> detail(String traceId) {
                return null;
            }
        });
        assertThat(service.list(1, 10).get("total")).isEqualTo(1);
    }

    @Test void listFiltersUseRealObservationFields() throws Exception {
        var server = server(new ArrayList<>(), """
                {"data":[
                  {"id":"ok1","traceId":"tr-ok","name":"askdb","type":"AGENT","startTime":"2026-10-07T01:00:00.000Z","endTime":"2026-10-07T01:00:01.000Z","input":{"text":"华东退货率"},"metadata":{"keel.agent":"askdb","keel.env":"prod","keel.status":"ok","langfuse.observation.type":"agent","langfuse.user.id":"u_7"}},
                  {"id":"gen","traceId":"tr-ok","parentObservationId":"ok1","name":"answer","type":"GENERATION","startTime":"2026-10-07T01:00:00.100Z","endTime":"2026-10-07T01:00:00.900Z","metadata":{"keel.agent":"askdb","langfuse.observation.type":"generation","gen_ai.request.model":"qwen-plus","gen_ai.usage.input_tokens":"100","gen_ai.usage.output_tokens":"20","keel.llm.key_alias":"askdb-prod"}},
                  {"id":"block","traceId":"tr-block","name":"guard.input","startTime":"2026-10-07T01:10:00.000Z","endTime":"2026-10-07T01:10:00.200Z","metadata":{"keel.agent":"askdb","keel.env":"prod","keel.status":"failed","langfuse.observation.type":"guardrail"}},
                  {"id":"old","traceId":"tr-old","name":"askdb","startTime":"2026-10-01T01:00:00.000Z","endTime":"2026-10-01T01:00:01.000Z","metadata":{"keel.agent":"askdb","keel.env":"prod","keel.status":"ok","langfuse.observation.type":"agent"}}
                ]}
                """);
        var service = new TraceQueryService(client(server), "https://jp.cloud.langfuse.com", "proj-1", Map.of("qwen-plus", new double[] {0.001, 0.002}));
        @SuppressWarnings("unchecked")
        var blocked = (List<Map<String, Object>>) service.list(1, 10, "", "prod", "blocked", null, null, false, null).get("items");
        assertThat(blocked).extracting(item -> item.get("traceId")).containsExactly("tr-block");
        @SuppressWarnings("unchecked")
        var ok = (List<Map<String, Object>>) service.list(1, 10, "askdb", "prod", "ok",
                java.time.Instant.parse("2026-10-06T00:00:00Z"), java.time.Instant.parse("2026-10-08T00:00:00Z"), false, null).get("items");
        assertThat(ok).extracting(item -> item.get("traceId")).containsExactly("tr-ok");
        assertThat(ok.getFirst().get("question")).isEqualTo("华东退货率");
        assertThat(ok.getFirst().get("userId")).isEqualTo("u_7");
        assertThat(ok.getFirst().get("tokens")).isEqualTo(120);
        assertThat(ok.getFirst().get("costCny")).isEqualTo(100 * 0.001 + 20 * 0.002);
        assertThat(ok.getFirst().get("durationMs")).isEqualTo(1000);
        var detail = service.detail("tr-ok");
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) detail.get("summary");
        @SuppressWarnings("unchecked")
        var breakdown = (List<Map<String, Object>>) detail.get("latencyBreakdown");
        assertThat(breakdown.stream().mapToInt(item -> (Integer) item.get("ms")).sum()).isEqualTo(summary.get("durationMs"));
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) detail.get("nodes");
        assertThat(nodes).anySatisfy(node -> {
            if ("answer".equals(node.get("name"))) {
                assertThat(node.get("model")).isEqualTo("qwen-plus");
                assertThat(node.get("tokens")).isEqualTo(120);
                assertThat(node.get("llmKeyAlias")).isEqualTo("askdb-prod");
            }
        });
        server.stop(0);
    }

    @Test void listKeepsOnlyTheSelectedEnvironment() throws Exception {
        var server = server(new ArrayList<>(), """
                {"data":[
                  {"id":"a","traceId":"tr-dev","name":"ask","startTime":"2026-10-04T03:00:00.000Z","endTime":"2026-10-04T03:00:01.000Z","metadata":{"keel.agent":"askdb","keel.llm.key_alias":"askdb-dev"}},
                  {"id":"b","traceId":"tr-test","name":"ask","startTime":"2026-10-04T03:00:00.000Z","endTime":"2026-10-04T03:00:01.000Z","metadata":{"keel.agent":"askdb","keel.env":"test"}}
                ]}
                """);
        var service = new TraceQueryService(client(server), "https://jp.cloud.langfuse.com", "proj-1", Map.of());
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) service.list(1, 10, "", "test").get("items");
        assertThat(items).extracting(item -> item.get("traceId")).containsExactly("tr-test");
        server.stop(0);
    }

    private LangfuseClient client(HttpServer server) {
        return new LangfuseClient("http://127.0.0.1:" + server.getAddress().getPort(), "pk", "sk");
    }

    private HttpServer server(List<String> paths, String body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
