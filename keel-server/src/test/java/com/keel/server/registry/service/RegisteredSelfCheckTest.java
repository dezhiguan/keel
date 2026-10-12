package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class RegisteredSelfCheckTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void checksTheReturnedTraceInTheRegisteredEnvironment() throws Exception { check("qa-agent", true); }
    @Test void rejectsAnotherAgentsRuntimeBeforeSpending() throws Exception { check("echo", false); }

    private void check(String runtimeName, boolean passed) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/health", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/manifest", exchange -> {
            byte[] body = ("{\"manifest\":{\"metadata\":{\"name\":\"" + runtimeName
                    + "\"},\"spec\":{\"runtime\":{\"language\":\"python\"}}}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        var calls = new AtomicInteger();
        var endpoints = new AgentEndpointClient() {
            @Override public Answer probe(String endpoint, String agent, String env) {
                calls.incrementAndGet();
                assertThat(agent).isEqualTo("qa-agent"); assertThat(env).isEqualTo("test");
                return new Answer("probe", "trace-selfcheck");
            }
        };
        var reads = new AtomicInteger();
        var langfuse = new LangfuseClient("", "", "") {
            @Override public JsonNode observationsByTrace(String trace) {
                assertThat(trace).isEqualTo("trace-selfcheck");
                if (reads.incrementAndGet() == 1) throw new IllegalStateException("temporary read failure");
                if (reads.get() == 2) return json.createObjectNode().set("data", json.createArrayNode());
                var body = json.createObjectNode(); body.putArray("data").addObject().put("id", "root"); return body;
            }
        };
        server.start();
        try {
            var report = new SelfCheckService(endpoints, langfuse).checkRegistered("qa-agent", "test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), json.readTree("{\"spec\":{\"runtime\":{\"language\":\"python\"}}}"));
            assertThat(report.passed()).isEqualTo(passed);
            assertThat(calls.get()).isEqualTo(passed ? 5 : 0);
        } finally { server.stop(0); }
    }
}
