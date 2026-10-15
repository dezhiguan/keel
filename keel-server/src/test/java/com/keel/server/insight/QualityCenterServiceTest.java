package com.keel.server.insight;

import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityCenterServiceTest {
    @Test void downvoteRateAndAddedCaseShowUpInTheDataset() throws Exception {
        var posted = new AtomicReference<String>();
        var items = new AtomicReference<>("{\"data\":[]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
            String body;
            if ("POST".equals(exchange.getRequestMethod()) && "/api/public/dataset-items".equals(path)) {
                var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                posted.set(raw);
                var id = raw.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
                items.set("{\"data\":[{\"id\":\"" + id + "\",\"metadata\":{\"traceId\":\"down-1\",\"source\":\"user-feedback\"}}]}");
                body = "{\"id\":\"" + id + "\"}";
            } else if ("/api/public/dataset-items".equals(path)) {
                body = items.get();
            } else if ("/api/public/v3/scores".equals(path)) {
                body = """
                        {"data":[
                          {"name":"user-feedback","value":1,"traceId":"up-1","metadata":{"keel.agent":"cs-bot"}},
                          {"name":"user-feedback","value":-1,"traceId":"down-1","comment":"过时","createdAt":"2026-10-15T01:00:00Z","metadata":{"keel.agent":"cs-bot"}},
                          {"name":"correctness","value":0.2,"traceId":"other","metadata":{"keel.agent":"cs-bot"}}
                        ]}""";
            } else if ("/api/public/v2/observations".equals(path) && query.contains("traceId=down-1")) {
                body = """
                        {"data":[{"type":"GENERATION","input":"还有活动吗","output":"去年的活动"}]}""";
            } else {
                body = "{\"data\":[]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var quality = new QualityCenterService(new LangfuseClient(base, "pk", "sk"), agent -> "cs-bot/seed");
            var before = quality.report("cs-bot");
            assertThat(before.connected()).isTrue();
            assertThat(before.feedbackCount()).isEqualTo(2);
            assertThat(before.downCount()).isEqualTo(1);
            assertThat(before.downRate()).isEqualTo(0.5);
            assertThat(before.cases()).singleElement().satisfies(row -> {
                assertThat(row.traceId()).isEqualTo("down-1");
                assertThat(row.input()).isEqualTo("还有活动吗");
                assertThat(row.output()).isEqualTo("去年的活动");
                assertThat(row.added()).isFalse();
            });
            var added = quality.add("cs-bot", "down-1");
            assertThat(added.dataset()).isEqualTo("cs-bot/seed");
            assertThat(posted.get()).contains("\"datasetName\":\"cs-bot/seed\"");
            assertThat(posted.get()).contains("还有活动吗");
            assertThat(posted.get()).contains("去年的活动");
            assertThat(posted.get()).contains("\"traceId\":\"down-1\"");
            assertThat(quality.report("cs-bot").cases().getFirst().added()).isTrue();
            assertThat(quality.add("cs-bot", "down-1").itemId()).isEqualTo(added.itemId());
            assertThatThrownBy(() -> quality.add("cs-bot", "up-1")).isInstanceOf(KeelException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test void unconfiguredLangfuseIsNotAZeroRate() {
        var quality = new QualityCenterService(new LangfuseClient("", "", ""), agent -> "demo/seed");
        var report = quality.report(null);
        assertThat(report.connected()).isFalse();
        assertThat(report.downRate()).isNull();
        assertThat(report.cases()).isEmpty();
    }
}
