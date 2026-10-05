package com.keel.llm;

import com.keel.common.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayTest {
    @Test void missingPriceRefusesCatalog() {
        assertThatThrownBy(() -> new ModelCatalog(Map.of(
                "qwen-plus", new ModelCatalog.Model("qwen-plus", BigDecimal.ZERO, new BigDecimal("0.000002"), "http://127.0.0.1:9", ""))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺单价");
    }

    @Test void listsKeysWithoutTheSecret() {
        var gateway = gateway(echoServer());
        var created = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
        var listed = gateway.listed();
        assertThat(listed).anySatisfy(key -> {
            assertThat(key.alias()).isEqualTo("echo-dev");
            assertThat(key.dailyBudgetCny()).isEqualByComparingTo("30");
            assertThat(key.spentCny()).isEqualByComparingTo("0");
            assertThat(key.blocked()).isFalse();
        });
        assertThat(listed.toString()).doesNotContain(created.key());
    }

    @Test void rejectsAliasOutsideAgentEnv() {
        var gateway = gateway(echoServer());
        assertThatThrownBy(() -> gateway.create("CodeReview", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false))
                .isInstanceOf(Gateway.GatewayException.class)
                .extracting(error -> ((Gateway.GatewayException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
    }

    @Test void pricedChatRecordsPositiveCny() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            write(exchange, 200, usage("qwen-plus", 1000, 100));
        });
        var gateway = gateway(server);
        var created = gateway.create("askdb-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
        var completion = gateway.complete("Bearer " + created.key(), "/v1/chat/completions",
                "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
        assertThat(completion.spend().costCny()).isGreaterThan(BigDecimal.ZERO);
        assertThat(completion.spend().model()).isEqualTo("qwen-plus");
        assertThat(completion.spend().latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(completion.spend().timedOut()).isFalse();
        assertThat(seen).containsExactly("qwen-plus");
        assertThat(gateway.spend("askdb-dev", 1, 20)).hasSize(1);
    }

    @Test void timeoutFallsBackOnce() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            if ("qwen-plus".equals(model)) {
                Thread.sleep(1000);
                return;
            }
            write(exchange, 200, usage("deepseek-v3", 10, 10));
        });
        var gateway = gateway(server);
        var created = gateway.create("askdb-dev", List.of("qwen-plus", "deepseek-v3"), List.of("deepseek-v3"), new BigDecimal("30"), true);
        var completion = gateway.complete("Bearer " + created.key(), "/v1/chat/completions",
                "{\"model\":\"qwen-plus\"}", Duration.ofMillis(200));
        assertThat(completion.spend().model()).isEqualTo("deepseek-v3");
        assertThat(completion.spend().timedOut()).isFalse();
        assertThat(seen).containsExactly("qwen-plus", "deepseek-v3");
        assertThat(gateway.spend("askdb-dev", 1, 10)).anySatisfy(row -> {
            assertThat(row.model()).isEqualTo("qwen-plus");
            assertThat(row.timedOut()).isTrue();
            assertThat(row.costCny()).isEqualByComparingTo("0");
        });
    }

    @Test void updateBudgetKeepsTheSameKey() {
        var gateway = gateway(echoServer());
        var created = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
        gateway.updateBudget("echo-dev", new BigDecimal("45"));
        assertThat(gateway.requireAlias("echo-dev").dailyBudgetCny()).isEqualByComparingTo("45");
        assertThat(gateway.requireAlias("echo-dev").token()).isEqualTo(created.key());
        assertThat(gateway.listed()).anySatisfy(key -> assertThat(key.dailyBudgetCny()).isEqualByComparingTo("45"));
    }

    @Test void providerComesFromTheUpstreamHost() {
        assertThat(ModelCatalog.provider("https://dashscope.aliyuncs.com/compatible-mode")).isEqualTo("DashScope");
        assertThat(ModelCatalog.provider("https://api.deepseek.com")).isEqualTo("DeepSeek");
        assertThat(ModelCatalog.provider("http://127.0.0.1:9")).isEmpty();
    }

    @Test void disabledFallbackDoesNotSwitchModel() throws Exception {
        var calls = new AtomicInteger();
        var server = server((model, exchange) -> {
            calls.incrementAndGet();
            write(exchange, 500, "");
        });
        var gateway = gateway(server);
        var created = gateway.create("rag-forge-dev", List.of("qwen-plus", "deepseek-v3"), List.of("deepseek-v3"), new BigDecimal("30"), false);
        assertThatThrownBy(() -> gateway.complete("Bearer " + created.key(), "/v1/chat/completions",
                "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                .isInstanceOf(Gateway.GatewayException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void spentBudgetReturnsRegisteredErrorCode() throws Exception {
        var server = server((model, exchange) -> write(exchange, 200, usage("qwen-plus", 1000, 100)));
        var budget = new BudgetCounter();
        var gateway = new Gateway(catalog(server), budget, new Upstream());
        var created = gateway.create("askdb-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.0001"), false);
        gateway.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
        assertThatThrownBy(() -> gateway.complete("Bearer " + created.key(), "/v1/chat/completions",
                "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                .isInstanceOf(Gateway.GatewayException.class)
                .extracting(error -> ((Gateway.GatewayException) error).code())
                .isEqualTo(ErrorCode.LLM_BUDGET_EXCEEDED);
    }

    @Test void secondReplicaAcceptsAKeyCreatedOnTheFirst() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            write(exchange, 200, usage("qwen-plus", 10, 4));
        });
        var shared = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var first = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), new KeyDirectory(new KeyDirectory.MapBackend(shared)));
        var second = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), new KeyDirectory(new KeyDirectory.MapBackend(shared)));
        var created = first.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
        var completion = second.complete("Bearer " + created.key(), "/v1/chat/completions",
                "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
        assertThat(completion.spend().alias()).isEqualTo("echo-dev");
        assertThat(seen).containsExactly("qwen-plus");
    }

    @Test void twoGatewaysShareOneBudgetCounter() throws Exception {
        var server = server((model, exchange) -> write(exchange, 200, usage("qwen-plus", 1000, 100)));
        var budget = new BudgetCounter();
        var first = new Gateway(catalog(server), budget, new Upstream());
        var second = new Gateway(catalog(server), budget, new Upstream());
        var created = first.create("askdb-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.0001"), false);
        second.create("askdb-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.0001"), false);
        first.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
        var other = second.requireAlias("askdb-dev");
        assertThatThrownBy(() -> second.complete("Bearer " + other.token(), "/v1/chat/completions",
                "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                .extracting(error -> ((Gateway.GatewayException) error).code())
                .isEqualTo(ErrorCode.LLM_BUDGET_EXCEEDED);
    }

    private static Gateway gateway(HttpServer server) {
        return new Gateway(catalog(server), new BudgetCounter(), new Upstream());
    }

    private static ModelCatalog catalog(HttpServer server) {
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ModelCatalog(Map.of(
                "qwen-plus", new ModelCatalog.Model("qwen-plus", new BigDecimal("0.0000008"), new BigDecimal("0.000002"), base, "vendor"),
                "deepseek-v3", new ModelCatalog.Model("deepseek-v3", new BigDecimal("0.000001"), new BigDecimal("0.000002"), base, "vendor"),
                "text-embedding-v3", new ModelCatalog.Model("text-embedding-v3", new BigDecimal("0.0000002"), new BigDecimal("0.0000002"), base, "vendor")));
    }

    private static String usage(String model, int input, int output) {
        return "{\"model\":\"" + model + "\",\"usage\":{\"prompt_tokens\":" + input + ",\"completion_tokens\":" + output + "}}";
    }

    private static HttpServer echoServer() {
        return server((model, exchange) -> write(exchange, 200, usage(model, 1, 1)));
    }

    private static HttpServer server(Handler handler) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                try {
                    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    var model = body.replaceAll(".*\"model\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                    handler.handle(model, exchange);
                } catch (Exception e) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    interface Handler {
        void handle(String model, com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }
}
