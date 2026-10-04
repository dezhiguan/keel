package com.keel.server.discovery;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatFlowTest {
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");

    @Test void heartbeatMarksTheInstanceReadyAndAStaleBeatIsOffline() {
        var book = new InstanceBook(null, Set.of("askdb"));
        book.beat("askdb", "dev", "local-1", "v1", NOW);
        var row = book.find("askdb", "dev", "local-1");
        assertThat(row.ready()).isTrue();
        assertThat(row.source()).isEqualTo("heartbeat");
        assertThat(row.lastSeen()).isEqualTo(NOW);
        book.expire(NOW.plus(ReconcileRules.HEARTBEAT).plusSeconds(1));
        assertThat(book.find("askdb", "dev", "local-1").ready()).isFalse();
        assertThat(ReconcileRules.classify(new ReconcileRules.Input(
                "ONLINE", true, true, false, row.lastSeen(), NOW.plusSeconds(46), "v1", "v1", true, true)))
                .contains(ReconcileRules.Kind.OFFLINE);
    }

    @Test void unknownAgentIsNotCreated() {
        var book = new InstanceBook(null, Set.of("askdb"));
        assertThatThrownBy(() -> book.beat("ghost", "dev", "1", "v1", NOW))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_NOT_FOUND);
        assertThat(book.find("ghost", "dev", "1")).isNull();
    }

    @Test void manifestFailureKeepsThePreviousVersion() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/manifest", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var checker = new ManifestVersionChecker();
        assertThat(checker.nextVersion("askdb", "v1", endpoint, new AgentEndpointClient())).isEqualTo("v1");
        server.stop(0);
    }

    @Test void manifestVersionReplacesTheInstanceVersion() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/manifest", exchange -> {
            var body = "{\"version\":\"v2\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var book = new InstanceBook(null, Set.of("askdb"));
        book.beat("askdb", "dev", "local-1", "v1", NOW);
        var remote = new ManifestVersionChecker().nextVersion("askdb", "v1", endpoint, new AgentEndpointClient());
        book.replaceVersion("askdb", "dev", "local-1", remote);
        assertThat(book.find("askdb", "dev", "local-1").version()).isEqualTo("v2");
        server.stop(0);
    }

    @Test void codeAgentsAreNotProbed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/health", exchange -> {
            var body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var book = new InstanceBook(null, Set.of("askdb"));
        var prober = new DifyProber(new AgentEndpointClient());
        prober.probe("code", "askdb", "dev", "local-1", endpoint, book, NOW);
        assertThat(book.find("askdb", "dev", "local-1")).isNull();
        prober.probe("dify", "askdb", "dev", "local-1", endpoint, book, NOW);
        assertThat(book.find("askdb", "dev", "local-1").source()).isEqualTo("probe");
        assertThat(book.find("askdb", "dev", "local-1").ready()).isTrue();
        server.stop(0);
    }
}