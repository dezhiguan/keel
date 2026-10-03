package com.keel.server.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.registry.service.ManifestValidator;
import com.keel.server.registry.service.SelfCheckService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestCheckTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ManifestValidator validator = new ManifestValidator();

    @Test void rejectsHighRiskWithoutApproval() throws Exception {
        var manifest = json.readTree("""
                {"apiVersion":"keel/v1","metadata":{"name":"askdb","owner":"数据平台组 / zhou"},
                 "spec":{"runtime":{"endpoint":"http://askdb"},"tools":[{"name":"sql.exec","risk":"high"}]}}
                """);
        assertThatThrownBy(() -> validator.validate(manifest, Set.of()))
                .isInstanceOf(KeelException.class)
                .extracting(e -> ((KeelException) e).code())
                .isEqualTo(ErrorCode.AGENT_MANIFEST_INVALID);
    }

    @Test void rejectsUngrantedSharedTool() throws Exception {
        var manifest = json.readTree("""
                {"apiVersion":"keel/v1","metadata":{"name":"askdb","owner":"数据平台组 / zhou"},
                 "spec":{"runtime":{"endpoint":"http://askdb"},"tools":[{"name":"git.pr.diff","risk":"low"}]}}
                """);
        assertThatThrownBy(() -> validator.validateShared(manifest, Set.of("git.pr.diff"), Set.of()))
                .isInstanceOf(KeelException.class);
    }

    @Test void healthFailureStillReturnsTheOtherItems() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/v1/health", exchange -> {
            var body = "down".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/invoke", exchange -> {
            calls.incrementAndGet();
            var eval = exchange.getRequestHeaders().getFirst("X-Keel-Eval-Run");
            var body = ("self-check".equals(eval) ? "event: final\ndata: {}\n\n" : "event: nope\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var report = new SelfCheckService().check("http://127.0.0.1:" + server.getAddress().getPort(), false, true);
            assertThat(report.passed()).isFalse();
            assertThat(report.items()).hasSize(4);
            assertThat(report.items().get(0).passed()).isFalse();
            assertThat(report.items().get(1).passed()).isTrue();
            assertThat(report.items().get(2).passed()).isFalse();
            assertThat(calls.get()).isEqualTo(5);
        } finally {
            server.stop(0);
        }
    }
}