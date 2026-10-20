package com.keel.server.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.insight.controller.InsightController;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyQuotaEditTest {
    @Test void quotaUpdatesRequireAnAdministrator() {
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        var costs = new CostService(new LiteLlmClient("", "")) {
            @Override public void updateBudget(String alias, BigDecimal budget, Map<String, Integer> limits) {
                called.set(true);
            }
        };
        var controller = new InsightController(null, null, costs, null, null, null);
        assertThatThrownBy(() -> controller.updateBudget("echo-dev", Map.of("dailyBudgetCny", 30, "rpm", 1)))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code()).isEqualTo(ErrorCode.AUTH_CONSOLE_FORBIDDEN);
        assertThat(called.get()).isFalse();
    }

    @Test void administratorCanClearLimitsAndTheProxyPreservesExplicitNulls() throws Exception {
        var captured = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/v1/keys/echo-dev/budget", exchange -> {
            captured.set(json.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        var request = new MockHttpServletRequest();
        ConsolePrincipal.set(request, ConsolePrincipal.dev());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var costs = new CostService(new LiteLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-admin"));
            var controller = new InsightController(null, null, costs, null, null, null);
            var body = new LinkedHashMap<String, Object>();
            body.put("dailyBudgetCny", 30);
            body.put("rpm", 1);
            body.put("tpm", null);
            body.put("concurrency", 2);
            controller.updateBudget("echo-dev", body);
            assertThat(captured.get().path("rpm").asInt()).isEqualTo(1);
            assertThat(captured.get().has("tpm")).isTrue();
            assertThat(captured.get().path("tpm").isNull()).isTrue();
            assertThat(captured.get().path("concurrency").asInt()).isEqualTo(2);
            assertThat(captured.get().path("dailyBudgetCny").decimalValue()).isEqualByComparingTo(new BigDecimal("30"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            server.stop(0);
        }
    }
}
