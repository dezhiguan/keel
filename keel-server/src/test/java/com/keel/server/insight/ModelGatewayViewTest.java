package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayViewTest {
    @Test void sectionsStopAtTheWindowAndSkipBillsThePageDoesNotShow() throws Exception {
        var now = Instant.now();
        var recent = now.minusSeconds(60).toString();
        var older = now.minusSeconds(3 * 86400).toString();
        var spendHits = new AtomicInteger();
        var paths = Collections.synchronizedList(new ArrayList<String>());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            paths.add(path + (query.isBlank() ? "" : "?" + query));
            String body = switch (path) {
                case "/admin/v1/spend" -> {
                    spendHits.incrementAndGet();
                    if (query.contains("cursor=more")) {
                        yield "{\"data\":[{\"alias\":\"askdb-dev\",\"model\":\"qwen\",\"costCny\":8,\"status\":\"SETTLED\",\"ts\":\"" + older + "\"}],\"nextCursor\":\"tail\"}";
                    }
                    if (query.contains("cursor=old")) {
                        yield "{\"data\":[{\"alias\":\"askdb-dev\",\"model\":\"qwen\",\"costCny\":9,\"status\":\"SETTLED\",\"ts\":\"" + older + "\"}],\"nextCursor\":\"more\"}";
                    }
                    yield "{\"data\":["
                            + "{\"alias\":\"askdb-dev\",\"model\":\"qwen\",\"costCny\":1,\"status\":\"SETTLED\",\"ts\":\"" + recent + "\"},"
                            + "{\"alias\":\"askdb-dev\",\"model\":\"qwen\",\"costCny\":0,\"status\":\"FAILED\",\"ts\":\"" + recent + "\"}"
                            + "],\"nextCursor\":\"old\"}";
                }
                case "/admin/v1/keys" -> "{\"data\":[{\"alias\":\"askdb-dev\",\"models\":[\"qwen\"],\"dailyBudgetCny\":30,\"spentCny\":1}]}";
                case "/admin/v1/models" -> "{\"data\":[{\"name\":\"qwen\",\"priceConfigured\":true}]}";
                case "/admin/v1/channels" -> "{\"data\":[{\"id\":\"ch-1\",\"name\":\"百炼\",\"model\":\"qwen\",\"env\":\"dev\"}]}";
                case "/admin/v1/logical-routes" -> "{\"data\":[{\"logical\":\"chat-default\",\"condition\":\"env=dev\",\"env\":\"dev\",\"primary\":\"qwen\",\"primaryWeight\":100}]}";
                case "/admin/v1/route-policy" -> "{\"version\":2,\"objective\":\"cost\"}";
                default -> "{\"data\":[]}";
            };
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var costs = new CostService(new LiteLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "admin"));
            var view = new ModelGatewayView(costs);
            var routes = view.get("dev", "24h", "routes");
            assertThat(spendHits.get()).isZero();
            assertThat(routes).containsKey("routePolicy");
            assertThat(routes).doesNotContainKey("bills");
            assertThat(paths).noneMatch(path -> path.contains("/admin/v1/spend") || path.contains("/alert-rules"));

            var channels = view.get("dev", "24h", "channels");
            assertThat(spendHits.get()).isEqualTo(2);
            assertThat(channels).doesNotContainKey("bills");
            assertThat(channels).containsKeys("models", "keys", "channels");
            assertThat(paths).anyMatch(path -> path.contains("cursor=old"));
            assertThat(paths).noneMatch(path -> path.contains("cursor=more"));

            var again = view.get("dev", "24h", "channels");
            assertThat(again).isSameAs(channels);
            assertThat(spendHits.get()).isEqualTo(2);

            costs.evict();
            @SuppressWarnings("unchecked")
            var failed = (List<java.util.Map<String, Object>>) view.get("dev", "24h", "keys").get("bills");
            assertThat(spendHits.get()).isEqualTo(4);
            assertThat(failed).singleElement().satisfies(row -> assertThat(row.get("status")).isEqualTo("FAILED"));
            assertThat(paths).noneMatch(path -> path.contains("cursor=more"));
        } finally {
            server.stop(0);
        }
    }

    @Test void blankTimestampsDoNotStopTheSpendScan() throws Exception {
        var older = Instant.now().minusSeconds(3 * 86400).toString();
        var cursors = Collections.synchronizedList(new ArrayList<String>());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            cursors.add(query);
            String body;
            if (query.contains("cursor=tail")) {
                body = "{\"data\":[],\"nextCursor\":\"\"}";
            } else if (query.contains("cursor=old")) {
                body = "{\"data\":[{\"alias\":\"askdb-dev\",\"model\":\"qwen\",\"costCny\":9,\"status\":\"SETTLED\",\"ts\":\"" + older + "\"}],\"nextCursor\":\"tail\"}";
            } else if (query.contains("cursor=blank")) {
                body = "{\"data\":[{\"alias\":\"askdb-dev\",\"model\":\"qwen\",\"costCny\":2,\"status\":\"SETTLED\",\"ts\":\"\"}],\"nextCursor\":\"old\"}";
            } else {
                body = "{\"data\":[{\"alias\":\"askdb-dev\",\"model\":\"qwen\",\"costCny\":1,\"status\":\"SETTLED\",\"ts\":\"not-a-time\"}],\"nextCursor\":\"blank\"}";
            }
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var rows = new LiteLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "admin")
                    .spendSince(Instant.now().minusSeconds(3600));
            assertThat(rows).extracting(LiteLlmClient.Spend::costCny).containsExactly(1d, 2d);
            assertThat(cursors).noneMatch(query -> query.contains("cursor=tail"));
            assertThat(cursors).anyMatch(query -> query.contains("cursor=old"));
        } finally {
            server.stop(0);
        }
    }
}
