package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class LedgerPaginationTest {
    @Test void followsAllPagesAndCountsAnAttemptOnlyOnce() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var next = exchange.getRequestURI().getQuery().contains("cursor=next");
            var row = "{\"attemptId\":\"a\",\"requestId\":\"r\",\"costCny\":80.0}";
            var body = next ? "{\"data\":[" + row + ",{\"attemptId\":\"fallback\",\"requestId\":\"r\",\"costCny\":0.03}],\"nextCursor\":null}"
                    : "{\"data\":[" + row + "],\"nextCursor\":\"next\"}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var rows = new LiteLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-admin").spendAll();
            assertThat(rows).hasSize(2);
            assertThat(rows.stream().mapToDouble(LiteLlmClient.Spend::costCny).sum()).isEqualTo(80.03);
        } finally { server.stop(0); }
    }

    @Test void repeatedCursorFailsInsteadOfPublishingTruncatedTotals() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var bytes = "{\"data\":[{\"attemptId\":\"a\"}],\"nextCursor\":\"same\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new LiteLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-admin");
            assertThatThrownBy(client::spendAll).isInstanceOf(IllegalStateException.class).hasMessageContaining("did not advance");
        } finally { server.stop(0); }
    }
}
