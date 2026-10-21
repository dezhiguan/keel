package com.keel.llm;

import com.keel.common.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayTest {
    @Test void responseRequestIdIsGeneratedByTheGatewayAndMatchesItsBill() throws Exception {
        var server = echoServer();
        try {
            var gateway = gateway(server);
            var created = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            var controller = new LlmController(gateway, catalog(server), new LlmConfiguration.LlmProperties());
            var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/v1/chat/completions");
            request.addHeader("X-Request-Id", "caller-cannot-choose-this");
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            controller.complete(request, response, "Bearer " + created.key(), "{\"model\":\"qwen-plus\"}");
            assertThat(response.getHeader("X-Request-Id")).isNotBlank().isNotEqualTo("caller-cannot-choose-this");
            assertThat(gateway.spend("echo-dev", 1, 10).getFirst().requestId()).isEqualTo(response.getHeader("X-Request-Id"));
        } finally {
            server.stop(0);
        }
    }

    @Test void quotaChangesReachOtherReplicasWithoutRotatingTheKeyAndCanBeCleared() throws Exception {
        var server = echoServer();
        try {
            var keys = new KeyDirectory();
            var first = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), keys);
            var second = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), keys);
            var created = first.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            first.updateBudget("echo-dev", new BigDecimal("30"), Map.of("rpm", 1, "tpm", 6000, "concurrency", 2));
            var shared = second.requireAlias("echo-dev");
            assertThat(shared.token()).isEqualTo(created.key());
            assertThat(shared.version()).isEqualTo(created.version());
            assertThat(shared.models()).containsExactly("qwen-plus");
            assertThat(shared.rpm()).isEqualTo(1);
            second.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            assertThatThrownBy(() -> second.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                    .isInstanceOf(Gateway.GatewayException.class)
                    .extracting(error -> ((Gateway.GatewayException) error).code()).isEqualTo(ErrorCode.LLM_RATE_LIMITED);
            first.updateBudget("echo-dev", new BigDecimal("45"));
            assertThat(second.requireAlias("echo-dev").rpm()).isEqualTo(1);
            var clear = new java.util.LinkedHashMap<String, Integer>();
            clear.put("rpm", null);
            first.updateBudget("echo-dev", new BigDecimal("45"), clear);
            assertThat(second.requireAlias("echo-dev").rpm()).isNull();
            assertThat(second.requireAlias("echo-dev").tpm()).isEqualTo(6000);
            second.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
        } finally {
            server.stop(0);
        }
    }

    @Test void invalidQuotaDoesNotPartiallyChangeTheBudget() {
        var server = echoServer();
        try {
            var gateway = gateway(server);
            gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            assertThatThrownBy(() -> gateway.updateBudget("echo-dev", new BigDecimal("45"), Map.of("rpm", 0)))
                    .isInstanceOf(Gateway.GatewayException.class);
            assertThat(gateway.requireAlias("echo-dev").dailyBudgetCny()).isEqualByComparingTo("30");
        } finally {
            server.stop(0);
        }
    }

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

    @Test void spendSurvivesASecondReplica() throws Exception {
        var server = echoServer();
        var log = new SpendLog(null);
        var first = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), new KeyDirectory(), log);
        var created = first.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
        first.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
        var second = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), new KeyDirectory(), log);
        var rows = second.spend("echo-dev", 1, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().model()).isEqualTo("qwen-plus");
        assertThat(rows.getFirst().latencyMs()).isGreaterThanOrEqualTo(0);
        server.stop(0);
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

    @Test void channelShareReplacesModelsAndRejectsTheNextCallOnThatChannel() throws Exception {
        var server = echoServer();
        try {
            var gateway = gateway(server);
            var created = gateway.create("echo-dev", List.of("qwen-plus", "chat-default"), List.of("deepseek-v3"), new BigDecimal("30"), true);
            gateway.updateChannels("echo-dev", List.of(new Gateway.Share("m-qwen-plus", 1)), Map.of("tpm", 1000));
            var key = gateway.requireAlias("echo-dev");
            assertThat(key.models()).containsExactly("chat-default", "qwen-plus");
            assertThat(key.rpm()).isNull();
            assertThat(key.tpm()).isEqualTo(1000);
            assertThat(key.shares()).containsExactly(new Gateway.Share("m-qwen-plus", 1));
            assertThat(key.dailyBudgetCny()).isEqualByComparingTo("30");
            gateway.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            assertThatThrownBy(() -> gateway.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                    .isInstanceOf(Gateway.GatewayException.class)
                    .extracting(error -> ((Gateway.GatewayException) error).code()).isEqualTo(ErrorCode.LLM_RATE_LIMITED);
            var added = gateway.addChannel("百炼B", "qwen-plus", "dev", 10);
            assertThatThrownBy(() -> gateway.updateChannels("echo-dev", List.of(new Gateway.Share(added.id(), 11)), Map.of()))
                    .isInstanceOf(Gateway.GatewayException.class)
                    .hasMessageContaining("厂商上限");
        } finally {
            server.stop(0);
        }
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
        // Enough for one bounded reservation, not for a second while the first cost is settled.
        var created = gateway.create("askdb-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.012"), false);
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

    @Test void rotatedKeyStaysBlockedAcrossReplicasAndRestart() throws Exception {
        var server = echoServer();
        try {
            var shared = new java.util.concurrent.ConcurrentHashMap<String, String>();
            var first = new Gateway(catalog(server), new BudgetCounter(), new Upstream(),
                    new KeyDirectory(new KeyDirectory.MapBackend(shared)));
            var second = new Gateway(catalog(server), new BudgetCounter(), new Upstream(),
                    new KeyDirectory(new KeyDirectory.MapBackend(shared)));
            var old = first.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            assertThat(old.version()).isEqualTo(1);
            var stale = first.requireAlias("echo-dev");
            var current = second.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            assertThat(current.version()).isEqualTo(2);
            assertThatThrownBy(() -> new KeyDirectory(new KeyDirectory.MapBackend(shared)).save(stale.asBlocked()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(first.requireAlias("echo-dev").version()).isEqualTo(2);
            assertThat(first.listed()).anySatisfy(key -> assertThat(key.version()).isEqualTo(2));
            var restarted = new Gateway(catalog(server), new BudgetCounter(), new Upstream(),
                    new KeyDirectory(new KeyDirectory.MapBackend(shared)));
            for (var gateway : List.of(first, second, restarted)) {
                assertThatThrownBy(() -> gateway.complete("Bearer " + old.key(), "/v1/chat/completions",
                        "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                        .isInstanceOf(Gateway.GatewayException.class)
                        .extracting(error -> ((Gateway.GatewayException) error).code())
                        .isEqualTo(ErrorCode.LLM_KEY_BLOCKED);
            }
            assertThat(ErrorCode.LLM_KEY_BLOCKED.http()).isEqualTo(401);
            assertThat(restarted.complete("Bearer " + current.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)).status()).isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    @Test void twoGatewaysShareOneBudgetCounter() throws Exception {
        var server = server((model, exchange) -> write(exchange, 200, usage("qwen-plus", 1000, 100)));
        var budget = new BudgetCounter();
        var first = new Gateway(catalog(server), budget, new Upstream());
        var second = new Gateway(catalog(server), budget, new Upstream());
        var created = first.create("askdb-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.012"), false);
        second.create("askdb-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.012"), false);
        first.complete("Bearer " + created.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
        var other = second.requireAlias("askdb-dev");
        assertThatThrownBy(() -> second.complete("Bearer " + other.token(), "/v1/chat/completions",
                "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                .extracting(error -> ((Gateway.GatewayException) error).code())
                .isEqualTo(ErrorCode.LLM_BUDGET_EXCEEDED);
    }

    @Test void fiftyConcurrentRequestsCannotReserveBeyondDailyBudget() throws Exception {
        var inFlight = new CountDownLatch(10);
        var finish = new CountDownLatch(1);
        var server = server((model, exchange) -> {
            inFlight.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            write(exchange, 200, usage(model, 1, 1));
        });
        try (var pool = Executors.newFixedThreadPool(50)) {
            var budget = new BudgetCounter();
            var gateway = new Gateway(catalog(server), budget, new Upstream());
            var key = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.11469"), false);
            var start = new CountDownLatch(1);
            var calls = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 50; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                                "{\"model\":\"qwen-plus\",\"max_tokens\":2}", Duration.ofSeconds(3));
                        return true;
                    } catch (Gateway.GatewayException e) {
                        assertThat(e.code()).isEqualTo(ErrorCode.LLM_BUDGET_EXCEEDED);
                        return false;
                    }
                }));
            }
            start.countDown();
            assertThat(inFlight.await(3, TimeUnit.SECONDS)).isTrue();
            var atPeak = budget.snapshot("echo-dev");
            assertThat(atPeak.settledCny()).isEqualByComparingTo("0");
            assertThat(atPeak.reservedCny()).isLessThanOrEqualTo(new BigDecimal("0.11469"));
            finish.countDown();
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
            var after = budget.snapshot("echo-dev");
            assertThat(after.reservedCny()).isEqualByComparingTo("0");
            assertThat(after.settledCny()).isLessThanOrEqualTo(new BigDecimal("0.11469"));
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }

    @Test void thinkingModelReservesFullOutputDespiteSmallMaxTokens() throws Exception {
        var calls = new AtomicInteger();
        var server = server((model, exchange) -> {
            calls.incrementAndGet();
            write(exchange, 200, usage(model, 1, 1));
        });
        try {
            var model = new ModelCatalog.Model("qwen3.8-flash", new BigDecimal("0.0000008"),
                    new BigDecimal("0.0000027"), "http://127.0.0.1:" + server.getAddress().getPort(),
                    "vendor", 4096, 131072);
            var gateway = new Gateway(new ModelCatalog(Map.of(model.name(), model)), new BudgetCounter(), new Upstream());
            var key = gateway.create("thinking-dev", List.of(model.name()), List.of(), new BigDecimal("0.01"), false);
            assertThatThrownBy(() -> gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen3.8-flash\",\"max_tokens\":1}", Duration.ofSeconds(2)))
                    .isInstanceOf(Gateway.GatewayException.class)
                    .extracting(error -> ((Gateway.GatewayException) error).code())
                    .isEqualTo(ErrorCode.LLM_BUDGET_EXCEEDED);
            assertThat(calls.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test void missingUsageStaysPendingWithoutZeroCost() throws Exception {
        var server = server((model, exchange) -> write(exchange, 200, "{\"model\":\"qwen-plus\",\"choices\":[]}"));
        try {
            var budget = new BudgetCounter();
            var gateway = new Gateway(catalog(server), budget, new Upstream());
            var key = gateway.create("pending-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.012"), false);
            var completion = gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            assertThat(completion.spend().status()).isEqualTo("PENDING");
            assertThat(completion.spend().costCny()).isNull();
            assertThat(completion.spend().reservedCny()).isGreaterThan(BigDecimal.ZERO);
            assertThat(gateway.spend("pending-dev", 1, 10)).singleElement().satisfies(row -> {
                assertThat(row.status()).isEqualTo("PENDING");
                assertThat(row.costCny()).isNull();
            });
            assertThat(budget.snapshot("pending-dev").reservedCny()).isGreaterThan(BigDecimal.ZERO);
            assertThatThrownBy(() -> gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                    .extracting(error -> ((Gateway.GatewayException) error).code())
                    .isEqualTo(ErrorCode.LLM_BUDGET_EXCEEDED);
        } finally {
            server.stop(0);
        }
    }

    @Test void pendingBillSurvivesRecentLedgerTrimming() {
        var log = new SpendLog(null);
        log.append(new Gateway.Spend("pending-dev", "qwen-plus", -1, -1, null, "pending-id", Instant.now(),
                1, false, "PENDING", new BigDecimal("0.5")));
        for (int i = 0; i < 5001; i++) {
            log.append(new Gateway.Spend("echo-dev", "qwen-plus", 1, 1, BigDecimal.ZERO, "complete-" + i,
                    Instant.now(), 1, false));
        }
        assertThat(log.all()).filteredOn(row -> "pending-id".equals(row.requestId())).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.costCny()).isNull();
        });
    }

    @Test void explicitZeroUsageIsSettledRatherThanPending() throws Exception {
        var server = server((model, exchange) -> write(exchange, 200, usage(model, 0, 0)));
        try {
            var budget = new BudgetCounter();
            var gateway = new Gateway(catalog(server), budget, new Upstream());
            var key = gateway.create("zero-dev", List.of("qwen-plus"), List.of(), new BigDecimal("0.012"), false);
            var completed = gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            assertThat(completed.spend().status()).isEqualTo("SETTLED");
            assertThat(completed.spend().costCny()).isEqualByComparingTo("0");
            assertThat(budget.snapshot("zero-dev").reservedCny()).isEqualByComparingTo("0");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void codingModelUsesTheSameDayListPrice() throws Exception {
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yaml"));
        var binder = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources));
        var properties = binder.bind("keel.llm", org.springframework.boot.context.properties.bind.Bindable
                .of(LlmConfiguration.LlmProperties.class)).get();
        var models = properties.getModels();
        assertThat(models.keySet()).containsExactlyInAnyOrder("qwen3.8-flash", "deepseek-flash");
        var flash = models.get("qwen3.8-flash");
        assertThat(flash.getInputCnyPerToken()).isEqualTo("0.0000008");
        assertThat(flash.getOutputCnyPerToken()).isEqualTo("0.0000027");
        assertThat(flash.getCacheCnyPerToken()).isEqualTo("0.0000001");
        var deepseek = models.get("deepseek-flash");
        assertThat(deepseek.getInputCnyPerToken()).isEqualTo("0.0000010083");
        assertThat(deepseek.getOutputCnyPerToken()).isEqualTo("0.0000040332");
        assertThat(deepseek.getCacheCnyPerToken()).isEqualTo("0.000000020166");
        var catalog = new ModelCatalog(Map.of("qwen3.8-flash", new ModelCatalog.Model(
                "qwen3.8-flash", new BigDecimal("0.0000008"), new BigDecimal("0.0000027"),
                "https://dashscope.aliyuncs.com/compatible-mode", "")));
        assertThat(catalog.require("qwen3.8-flash").priceConfigured()).isTrue();
    }

    @Test void settledCostsMatchTheBudgetAndFailedAttemptsStayOut() throws Exception {
        var server = server((model, exchange) -> {
            if ("qwen-plus".equals(model)) {
                write(exchange, 500, "");
                return;
            }
            write(exchange, 200, usage(model, 1000, 100));
        });
        try {
            var budget = new BudgetCounter();
            var ledger = new MemorySpendLedger();
            var gateway = new Gateway(catalog(server), budget, new Upstream(), new KeyDirectory(), new SpendLog(null), ledger, new Admission(null));
            var key = gateway.create("askdb-dev", List.of("qwen-plus", "deepseek-v3"), List.of("deepseek-v3"), new BigDecimal("30"), true);
            gateway.complete("Bearer " + key.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            var settled = ledger.page(null, null, 20).rows().stream()
                    .filter(row -> "SETTLED".equals(row.status()))
                    .map(Gateway.Spend::costCny)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(settled).isEqualByComparingTo(budget.snapshot("askdb-dev").settledCny());
            assertThat(ledger.page(null, null, 20).rows()).anySatisfy(row -> {
                assertThat(row.status()).isEqualTo("FAILED");
                assertThat(row.costCny()).isEqualByComparingTo("0");
                assertThat(row.routeVersion()).isEqualTo("primary");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test void cursorPageDoesNotMoveWhenANewerBillArrives() {
        var ledger = new MemorySpendLedger();
        var older = spendAt("a", "2026-10-15T00:00:00Z");
        var middle = spendAt("b", "2026-10-15T00:01:00Z");
        var newest = spendAt("c", "2026-10-15T00:02:00Z");
        ledger.append(older);
        ledger.append(middle);
        ledger.append(newest);
        var first = ledger.page(null, null, 2);
        assertThat(first.rows()).extracting(Gateway.Spend::attemptId).containsExactly("c", "b");
        ledger.append(spendAt("d", "2026-10-15T00:03:00Z"));
        var second = ledger.page(null, first.nextCursor(), 2);
        assertThat(second.rows()).extracting(Gateway.Spend::attemptId).containsExactly("a");
        assertThat(second.nextCursor()).isNull();
    }

    @Test void cancelledStreamSettlesReceivedUsageAndPendingWithoutIt() throws Exception {
        var withUsage = server((model, exchange) -> {
            var bytes = "data: {\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().flush();
            Thread.sleep(2000);
            exchange.close();
        });
        try {
            var budget = new BudgetCounter();
            var gateway = new Gateway(catalog(withUsage), budget, new Upstream());
            var key = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            var spend = gateway.stream("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\",\"stream\":true}", Duration.ofSeconds(2), throwingAfter(1), "trace-1");
            assertThat(spend.status()).isEqualTo("SETTLED");
            assertThat(spend.costCny()).isEqualByComparingTo("0.000007");
            assertThat(spend.traceId()).isEqualTo("trace-1");
            assertThat(lastRequestBody).contains("include_usage");
            assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo("0");
        } finally {
            withUsage.stop(0);
        }
        var withoutUsage = server((model, exchange) -> {
            var bytes = "data: {\"choices\":[]}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().flush();
            Thread.sleep(2000);
            exchange.close();
        });
        try {
            var budget = new BudgetCounter();
            var gateway = new Gateway(catalog(withoutUsage), budget, new Upstream());
            var key = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            var spend = gateway.stream("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\",\"stream\":true}", Duration.ofSeconds(2), throwingAfter(1), "");
            assertThat(spend.status()).isEqualTo("PENDING");
            assertThat(spend.costCny()).isNull();
            assertThat(budget.snapshot("echo-dev").reservedCny()).isGreaterThan(BigDecimal.ZERO);
        } finally {
            withoutUsage.stop(0);
        }
    }

    @Test void streamingAdmissionErrorIs429JsonInsteadOfAnEmpty500() throws Exception {
        var server = echoServer();
        try {
            var gateway = gateway(server);
            var key = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false, 1, null, null);
            gateway.complete("Bearer " + key.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            var controller = new LlmController(gateway, catalog(server), new LlmConfiguration.LlmProperties());
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/chat/completions")
                    .header("Authorization", "Bearer " + key.key())
                    .accept("text/event-stream").contentType("application/json")
                    .content("{\"model\":\"qwen-plus\",\"stream\":true}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/json"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("LLM_RATE_LIMITED"));
        } finally {
            server.stop(0);
        }
    }

    @Test void rateLimitRejectsTheSecondCallWithoutReservingMoreBudget() throws Exception {
        var server = echoServer();
        try {
            var budget = new BudgetCounter();
            var gateway = new Gateway(catalog(server), budget, new Upstream());
            var key = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false, 1, null, null);
            gateway.complete("Bearer " + key.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            var reserved = budget.snapshot("echo-dev").reservedCny();
            assertThatThrownBy(() -> gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                    .extracting(error -> ((Gateway.GatewayException) error).code())
                    .isEqualTo(ErrorCode.LLM_RATE_LIMITED);
            assertThat(budget.snapshot("echo-dev").reservedCny()).isEqualByComparingTo(reserved);
            var denied = gateway.spend("echo-dev", 1, 10).stream()
                    .filter(row -> row.routeVersion().equals("rejected:LLM_RATE_LIMITED")).findFirst().orElseThrow();
            assertThat(denied.status()).isEqualTo("FAILED");
            assertThat(denied.costCny()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(denied.reservedCny()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(denied.inputTokens() + denied.outputTokens()).isZero();
            assertThat(denied.timedOut()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test void cacheTokensUseTheCachePrice() throws Exception {
        var server = server((model, exchange) -> write(exchange, 200,
                "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":4}}}"));
        try {
            var priced = new ModelCatalog.Model("qwen-plus", new BigDecimal("0.0000008"), new BigDecimal("0.000002"),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "vendor", 4096, 4096, "v9", new BigDecimal("0.0000001"), false);
            var gateway = new Gateway(new ModelCatalog(Map.of("qwen-plus", priced)), new BudgetCounter(), new Upstream());
            var key = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            var spend = gateway.complete("Bearer " + key.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)).spend();
            assertThat(spend.cacheTokens()).isEqualTo(4);
            assertThat(spend.priceVersion()).isEqualTo("v9");
            assertThat(spend.costCny()).isEqualByComparingTo("0.000007");
            assertThat(gateway.reports()).anySatisfy(report -> {
                assertThat(report.health()).isEqualTo("LIVE");
                assertThat(report.drift()).isFalse();
            });
        } finally {
            server.stop(0);
        }
    }

    @Test void embeddingCallDoesNotFallBack() throws Exception {
        var calls = new AtomicInteger();
        var server = server((model, exchange) -> {
            calls.incrementAndGet();
            write(exchange, 500, "");
        });
        try {
            var embedding = new ModelCatalog.Model("text-embedding-v3", new BigDecimal("0.0000002"), BigDecimal.ZERO,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "vendor", 8192, 0, "", null, true);
            var chat = new ModelCatalog.Model("qwen-plus", new BigDecimal("0.0000008"), new BigDecimal("0.000002"),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "vendor");
            var gateway = new Gateway(new ModelCatalog(Map.of(embedding.name(), embedding, chat.name(), chat)), new BudgetCounter(), new Upstream());
            var key = gateway.create("embed-dev", List.of("text-embedding-v3", "qwen-plus"), List.of("qwen-plus"), new BigDecimal("30"), true);
            assertThatThrownBy(() -> gateway.complete("Bearer " + key.key(), "/v1/embeddings",
                    "{\"model\":\"text-embedding-v3\"}", Duration.ofSeconds(2)))
                    .isInstanceOf(Gateway.GatewayException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test void failureSwitchesOnceAndAuthDoesNot() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            if ("qwen-plus".equals(model)) {
                write(exchange, 500, "");
                return;
            }
            if ("deepseek-v3".equals(model)) {
                write(exchange, 500, "");
                return;
            }
            write(exchange, 200, usage(model, 1, 1));
        });
        try {
            var gateway = gateway(server);
            var key = gateway.create("askdb-dev", List.of("qwen-plus", "deepseek-v3", "text-embedding-v3"),
                    List.of("deepseek-v3", "text-embedding-v3"), new BigDecimal("30"), true);
            var preview = gateway.preview("askdb-dev", "qwen-plus");
            assertThatThrownBy(() -> gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2)))
                    .isInstanceOf(Gateway.GatewayException.class);
            assertThat(seen).containsExactlyElementsOf(preview.models());
            assertThat(seen).hasSize(2);
        } finally {
            server.stop(0);
        }
        seen.clear();
        var denied = server((model, exchange) -> {
            seen.add(model);
            write(exchange, 401, "");
        });
        try {
            var gateway = gateway(denied);
            var key = gateway.create("askdb-dev", List.of("qwen-plus", "deepseek-v3"), List.of("deepseek-v3"), new BigDecimal("30"), true);
            var completion = gateway.complete("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            assertThat(completion.status()).isEqualTo(401);
            assertThat(seen).containsExactly("qwen-plus");
        } finally {
            denied.stop(0);
        }
    }

    @Test void previewMatchesThePublishedCostRouteAndRollbackRestoresIt() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            if ("deepseek-v3".equals(model)) {
                write(exchange, 500, "");
                return;
            }
            write(exchange, 200, usage(model, 1, 1));
        });
        try {
            var gateway = gateway(server);
            gateway.create("askdb-dev", List.of("qwen-plus", "deepseek-v3"), List.of("deepseek-v3", "qwen-plus"), new BigDecimal("30"), true);
            var published = gateway.publishRoute("cost");
            assertThat(published.version()).isEqualTo(2);
            var preview = gateway.preview("askdb-dev", "deepseek-v3");
            gateway.complete("Bearer " + gateway.requireAlias("askdb-dev").token(), "/v1/chat/completions",
                    "{\"model\":\"deepseek-v3\"}", Duration.ofSeconds(2));
            assertThat(seen).containsExactlyElementsOf(preview.models());
            assertThat(gateway.rollbackRoute().objective()).isEqualTo("balanced");
            assertThat(gateway.routePolicy().version()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private static Gateway.Spend spendAt(String attemptId, String instant) {
        return new Gateway.Spend("echo-dev", "qwen-plus", 1, 1, BigDecimal.ONE, "request",
                Instant.parse(instant), 1, false, "SETTLED", BigDecimal.ZERO, attemptId, "", "v", "primary", -1);
    }

    private static java.io.OutputStream throwingAfter(int writes) {
        return new java.io.OutputStream() {
            private int seen;

            @Override
            public void write(byte[] buffer, int offset, int length) throws java.io.IOException {
                seen++;
                if (seen >= writes) {
                    throw new java.io.IOException("client closed");
                }
            }

            @Override
            public void write(int value) throws java.io.IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }
        };
    }

    private static Gateway gateway(HttpServer server) {
        return new Gateway(catalog(server), new BudgetCounter(), new Upstream());
    }

    private static ModelCatalog catalog(HttpServer server) {
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ModelCatalog(Map.of(
                "qwen-plus", new ModelCatalog.Model("qwen-plus", new BigDecimal("0.0000008"), new BigDecimal("0.000002"), base, "vendor", 4096, 4096),
                "deepseek-v3", new ModelCatalog.Model("deepseek-v3", new BigDecimal("0.000001"), new BigDecimal("0.000002"), base, "vendor", 4096, 4096),
                "text-embedding-v3", new ModelCatalog.Model("text-embedding-v3", new BigDecimal("0.0000002"), new BigDecimal("0.0000002"), base, "vendor", 4096, 4096)));
    }

    private static String usage(String model, int input, int output) {
        return "{\"model\":\"" + model + "\",\"usage\":{\"prompt_tokens\":" + input + ",\"completion_tokens\":" + output + "}}";
    }

    @Test void aProbeIsVisibleOnAnotherGatewaySharingTheMarkStore() throws Exception {
        var server = echoServer();
        try {
            var marks = new ProbeMarks(null);
            var first = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), new KeyDirectory(),
                    new SpendLog(null), null, new Admission(null), marks);
            var second = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), new KeyDirectory(),
                    new SpendLog(null), null, new Admission(null), marks);
            first.probe("qwen-plus");
            assertThat(second.reports()).anySatisfy(report -> {
                if ("qwen-plus".equals(report.model().name())) {
                    assertThat(report.health()).isEqualTo("PROBED");
                }
            });
        } finally {
            server.stop(0);
        }
    }

    @Test void aProbeMarksTheModelBeforeAnySettledCall() throws Exception {
        var server = echoServer();
        try {
            var gateway = gateway(server);
            assertThat(gateway.reports()).anySatisfy(report -> assertThat(report.health()).isEqualTo("CONFIGURED"));
            gateway.probe("qwen-plus");
            assertThat(gateway.reports()).anySatisfy(report -> {
                if ("qwen-plus".equals(report.model().name())) {
                    assertThat(report.health()).isEqualTo("PROBED");
                }
            });
        } finally {
            server.stop(0);
        }
    }

    @Test void trippedAddedChannelIsSkippedWhileTheBuiltinChannelStillServes() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            write(exchange, 200, usage(model, 1, 1));
        });
        try {
            var gateway = new Gateway(catalog(server), new BudgetCounter(), new Upstream(), new KeyDirectory(),
                    new SpendLog(null), null, new Admission(null), new ProbeMarks(null), new RoutePolicy(),
                    new AlertRules(null), new RequestDefaults(""), new ChannelBoard(null));
            var prod = gateway.create("careermate-prod", List.of("qwen-plus", "deepseek-v3"), List.of("deepseek-v3"),
                    new BigDecimal("30"), true);
            var dev = gateway.create("careermate-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            var added = gateway.addChannel("百炼B", "qwen-plus", "prod", 1200);
            assertThat(gateway.switchProd(added.id()).moved()).isEqualTo(1);
            assertThat(gateway.simulateChannel(added.id()).breaker()).isEqualTo("open");
            gateway.complete("Bearer " + prod.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            gateway.complete("Bearer " + dev.key(), "/v1/chat/completions", "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            assertThat(seen).containsExactly("deepseek-v3", "qwen-plus");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer echoServer() {
        return server((model, exchange) -> write(exchange, 200, usage(model, 1, 1)));
    }

    private static volatile String lastRequestBody;

    @Test void logicalModelResolvesBeforeTheUpstreamCall() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            write(exchange, 200, usage("qwen3.8-flash", 4, 2));
        });
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var flash = new ModelCatalog.Model("qwen3.8-flash", new BigDecimal("0.0000008"), new BigDecimal("0.0000027"),
                    base, "vendor", 4096, 4096);
            var gateway = new Gateway(new ModelCatalog(Map.of("qwen3.8-flash", flash)), new BudgetCounter(), new Upstream());
            var created = gateway.create("echo-dev", List.of("chat-default"), List.of(),
                    new BigDecimal("30"), false);
            var completion = gateway.complete("Bearer " + created.key(), "/v1/chat/completions",
                    "{\"model\":\"chat-default\"}", Duration.ofSeconds(2));
            assertThat(completion.status()).isEqualTo(200);
            assertThat(completion.spend().model()).isEqualTo("qwen3.8-flash");
            assertThat(seen).containsExactly("qwen3.8-flash");
        } finally {
            server.stop(0);
        }
    }

    @Test void retiredAliasUsesTheCurrentCatalogModel() throws Exception {
        var seen = new ArrayList<String>();
        var server = server((model, exchange) -> {
            seen.add(model);
            write(exchange, 200, usage("qwen3.8-flash", 4, 2));
        });
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var flash = new ModelCatalog.Model("qwen3.8-flash", new BigDecimal("0.0000008"), new BigDecimal("0.0000027"),
                    base, "vendor", 4096, 4096);
            var plus = new ModelCatalog.Model("qwen-plus", new BigDecimal("0.0000008"), new BigDecimal("0.000002"),
                    base, "vendor", 4096, 4096);
            var keys = new KeyDirectory();
            var issuer = new Gateway(new ModelCatalog(Map.of("qwen-plus", plus, "qwen3.8-flash", flash)),
                    new BudgetCounter(), new Upstream(), keys);
            var created = issuer.create("chat-demo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            var live = new Gateway(new ModelCatalog(Map.of("qwen3.8-flash", flash)), new BudgetCounter(), new Upstream(), keys);
            var completion = live.complete("Bearer " + created.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\"}", Duration.ofSeconds(2));
            assertThat(completion.status()).isEqualTo(200);
            assertThat(completion.spend().model()).isEqualTo("qwen3.8-flash");
            assertThat(seen).containsExactly("qwen3.8-flash");
        } finally {
            server.stop(0);
        }
    }

    @Test void streamDropsOptionsAfterTheUpstreamRejectsThem() throws Exception {
        var bodies = new ArrayList<String>();
        var server = server((model, exchange) -> {
            bodies.add(lastRequestBody);
            if (lastRequestBody.contains("stream_options")) {
                write(exchange, 500, "{\"error\":{\"message\":\"stream_options\"}}");
                return;
            }
            var payload = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        try {
            var gateway = gateway(server);
            var key = gateway.create("echo-dev", List.of("qwen-plus"), List.of(), new BigDecimal("30"), false);
            var out = new java.io.ByteArrayOutputStream();
            gateway.stream("Bearer " + key.key(), "/v1/chat/completions",
                    "{\"model\":\"qwen-plus\",\"stream\":true}", Duration.ofSeconds(2), out, "trace-stream");
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(1)).doesNotContain("stream_options");
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("你");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(Handler handler) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                try {
                    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    lastRequestBody = body;
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
