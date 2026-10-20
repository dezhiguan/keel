package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SelfCheckAuthenticationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void protectedMetaAgentReceivesServiceIdentityOnManifestAndEveryProbe() throws Exception {
        check("meta-agent", 200, true);
    }
    @Test void otherAgentsNeverReceiveMetaAgentCredential() throws Exception {
        check("qa-agent", 200, true);
    }
    @Test void authenticationFailureIsNotReportedAsManifestMismatchOrMissingTrace() throws Exception {
        check("meta-agent", 401, false);
        check("meta-agent", 403, false);
    }

    private void check(String agent, int status, boolean passed) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var probes = new AtomicInteger();
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var health = path.equals("/v1/health");
            if (!health) assertThat(exchange.getRequestHeaders().getFirst("X-Keel-Bridge"))
                    .isEqualTo(agent.equals("meta-agent") ? "test-bridge-identity" : null);
            int responseStatus = health ? 200 : status;
            String body = "{}";
            if (!health && status == 200) {
                if (path.equals("/v1/manifest")) body = "{\"manifest\":{\"metadata\":{\"name\":\"" + agent
                        + "\"},\"spec\":{\"runtime\":{\"language\":\"python\"}}}}";
                else {
                    probes.incrementAndGet();
                    assertThat(exchange.getRequestHeaders().getFirst("X-Keel-Eval-Run")).isEqualTo("self-check");
                    assertThat(exchange.getRequestHeaders().getFirst("X-Keel-Agent")).isEqualTo(agent);
                    assertThat(exchange.getRequestHeaders().getFirst("X-Keel-Env")).isEqualTo("test");
                    body = "event: final\ndata: {\"answer\":\"probe\",\"trace_id\":\"authenticated-probe\"}\n\n";
                }
            }
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        var langfuse = new LangfuseClient("", "", "") {
            @Override public com.fasterxml.jackson.databind.JsonNode observationsByTrace(String trace) {
                assertThat(trace).isEqualTo("authenticated-probe");
                var response = json.createObjectNode();
                response.putArray("data").addObject().put("id", "root");
                return response;
            }
        };
        server.start();
        try {
            var service = new SelfCheckService(new AgentEndpointClient(), langfuse);
            ReflectionTestUtils.setField(service, "metaBridgeToken", "test-bridge-identity");
            var result = service.checkRuntime(agent, "test", "http://127.0.0.1:" + server.getAddress().getPort(),
                    json.readTree("{\"spec\":{\"runtime\":{\"language\":\"python\"}}}"));
            assertThat(result.passed()).isEqualTo(passed);
            assertThat(probes.get()).isEqualTo(passed ? 5 : 0);
            if (!passed) {
                assertThat(result.items().get(1).detail()).contains("认证失败", "HTTP " + status).doesNotContain("不一致");
                assertThat(result.items().get(2).detail()).contains("协议自检未完成");
            }
        } finally { server.stop(0); }
    }
}
