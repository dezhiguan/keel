package com.keel.server.integration.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEndpointClientTest {
    @Test void readsTheFinalAnswerAndTraceId() {
        var answer = AgentEndpointClient.parseFinal("""
                event: step
                data: {"name":"llm.chat"}

                event: final
                data: {"answer":"你好","trace_id":"abc"}
                """);
        assertThat(answer.text()).isEqualTo("你好");
        assertThat(answer.traceId()).isEqualTo("abc");
    }

    @Test void postsTheResumeBodyAndRefusesAFailedAgent() throws Exception {
        var seen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs/run_1/resume", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/v1/runs/run_down/resume", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            new AgentEndpointClient().resume(base + "/", "run_1", "rt_1", "approve", null);
            assertThat(seen.get()).contains("\"resume_token\":\"rt_1\"").contains("\"decision\":\"approve\"");
            new AgentEndpointClient().resume(base, "run_1", null, null, "先修这一台", "consent_1");
            assertThat(seen.get()).contains("\"text\":\"先修这一台\"").contains("\"consent_id\":\"consent_1\"");
            assertThatThrownBy(() -> new AgentEndpointClient().resume(base, "run_down", "rt", null, "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("503");
            assertThatThrownBy(() -> new AgentEndpointClient().resume("http://127.0.0.1:1", "run_1", "rt", null, "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("调用智能体恢复失败");
        } finally {
            server.stop(0);
        }
    }

    @Test void invokeNamesTheRegisteredAgentAndEnvironment() throws Exception {
        var headers = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/invoke", exchange -> {
            headers.set(exchange.getRequestHeaders());
            var body = """
                    event: final
                    data: {"answer":"ok","trace_id":"tr","run_id":"run"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var answer = new AgentEndpointClient().invoke(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "hi", null, "prompt-lab", "dev");
            assertThat(answer.text()).isEqualTo("ok");
            assertThat(headers.get().getFirst("X-Keel-Agent")).isEqualTo("prompt-lab");
            assertThat(headers.get().getFirst("X-Keel-Env")).isEqualTo("dev");
        } finally {
            server.stop(0);
        }
    }
    @Test void rejectsAnErrorEvent() {
        assertThatThrownBy(() -> AgentEndpointClient.parseFinal("event: error\ndata: {\"code\":\"SERVER_INTERNAL_ERROR\"}\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVER_INTERNAL_ERROR");
    }
}
