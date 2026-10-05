package com.keel.server.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.integration.ragforge.RagForgeInsightClient;
import com.keel.server.registry.service.AgentRegistryService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostServiceTest {
    @Test void twoSpendPagesSumToTheOverviewAndUnpricedModelsAreFlagged() throws Exception {
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            paths.add(path);
            String body = switch (path) {
                case "/admin/v1/spend" -> query.contains("page=1") ? page(50, "askdb-dev", "qwen-plus", 1)
                        : query.contains("page=2") ? page(1, "wind-prod", "deepseek-v3", 3)
                        : "{\"data\":[]}";
                case "/admin/v1/models" -> "{\"data\":[{\"name\":\"qwen-plus\",\"priceConfigured\":true},{\"name\":\"draft\",\"priceConfigured\":false}]}";
                case "/admin/v1/keys" -> "{\"data\":[{\"alias\":\"askdb-dev\",\"models\":[\"qwen-plus\"],\"dailyBudgetCny\":30,\"spentCny\":1.5,\"blocked\":false}]}";
                case "/api/public/v3/scores" -> "{\"data\":[{\"name\":\"askdb\",\"value\":0.8},{\"name\":\"wind\",\"value\":1.0}]}";
                default -> "{\"data\":[]}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var gateway = new LiteLlmClient(base, "admin");
        var langfuse = new LangfuseClient(base, "pk", "sk");
        var costs = new CostService(gateway);
        var result = costs.cost();
        assertThat(result.totalCny()).isEqualTo(53.0);
        assertThat(result.byAgent().get("askdb") + result.byAgent().get("wind")).isEqualTo(result.totalCny());
        assertThat(result.keys()).anySatisfy(key -> {
            assertThat(key.alias()).isEqualTo("askdb-dev");
            assertThat(key.agent()).isEqualTo("askdb");
            assertThat(key.env()).isEqualTo("dev");
            assertThat(key.dailyBudgetCny()).isEqualTo(30);
            assertThat(key.spentCny()).isEqualTo(1.5);
        });
        assertThat(result.models()).anySatisfy(model -> {
            if ("draft".equals(model.name())) {
                assertThat(model.priceConfigured()).isFalse();
            }
        });
        assertThat(result.stats()).anySatisfy(model -> {
            assertThat(model.name()).isEqualTo("qwen-plus");
            assertThat(model.provider()).isEqualTo("DashScope");
            assertThat(model.role()).isEqualTo("主力");
            assertThat(model.calls()).isEqualTo(50);
            assertThat(model.costCny()).isEqualTo(50.0);
            assertThat(model.p95()).isNull();
            assertThat(model.timeoutRate()).isNull();
            assertThat(model.status()).isEqualTo("ok");
        });
        assertThat(result.stats()).anySatisfy(model -> {
            assertThat(model.name()).isEqualTo("deepseek-v3");
            assertThat(model.provider()).isEqualTo("DeepSeek");
            assertThat(model.role()).isEqualTo("降级备选");
            assertThat(model.calls()).isEqualTo(1);
            assertThat(model.costCny()).isEqualTo(3.0);
        });
        assertThat(result.stats()).anySatisfy(model -> {
            assertThat(model.name()).isEqualTo("draft");
            assertThat(model.priceConfigured()).isFalse();
            assertThat(model.calls()).isZero();
        });
        var registry = new AgentRegistryService(null, null, null, new ObjectMapper()) {
            @Override public List<com.keel.server.registry.model.dto.AgentSummary> listAll() { return List.of(); }
        };
        var overview = new OverviewService(registry, costs, new QualityService(langfuse), () -> 0).overview("all", "24h");
        assertThat(overview.kpi().modelCostCny()).isEqualTo(1.5);
        assertThat(overview.kpi().calls()).isZero();
        assertThat(overview.kpi().avgScore()).isEqualTo(0.9);
        assertThat(overview.kpi().gateThreshold()).isEqualTo(0.85);
        assertThat(overview.kpi().pendingApprovals()).isZero();
        assertThat(overview.costByAgent().stream().mapToDouble(row -> (Double) row.get("costCny")).sum()).isEqualTo(1.5);
        assertThat(paths).noneMatch(path -> path.contains("/spend/logs") || path.contains("/key/generate")
                || path.equals("/api/public/metrics") || path.equals("/api/public/scores") || path.equals("/api/public/traces"));
        var monitor = new SharedServiceMonitor(new RagForgeInsightClient("", "", ""), new LiteLlmClient("", ""));
        @SuppressWarnings("unchecked")
        var service = ((List<java.util.Map<String, Object>>) monitor.services().get("services")).get(0);
        assertThat(service.get("p95")).isNull();
        server.stop(0);
    }

    @Test void langfuseGenerationsFillCallsAndP95WhenTheGatewayLedgerIsEmpty() throws Exception {
        var start = Instant.now().minusSeconds(1);
        var end = Instant.now();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/admin/v1/spend", "/admin/v1/keys" -> "{\"data\":[]}";
                case "/admin/v1/models" -> "{\"data\":[{\"name\":\"qwen-plus\",\"priceConfigured\":true,\"provider\":\"DashScope\"},{\"name\":\"deepseek-v3\",\"priceConfigured\":true}]}";
                case "/api/public/v2/observations" -> """
                        {"data":[{"type":"GENERATION","name":"llm.chat","model":"qwen-plus","startTime":"%s","endTime":"%s","metadata":{"keel.llm.key_alias":"askdb-dev","keel.fallback_from":"deepseek-v3"}}],"meta":{}}
                        """.formatted(start, end);
                default -> "{\"data\":[]}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var costs = new CostService(new LiteLlmClient(base, "admin"), new LangfuseClient(base, "pk", "sk"), (agent, env) -> { });
        var result = costs.cost("dev", "24h");
        assertThat(result.stats()).anySatisfy(model -> {
            assertThat(model.name()).isEqualTo("qwen-plus");
            assertThat(model.provider()).isEqualTo("DashScope");
            assertThat(model.calls()).isEqualTo(1);
            assertThat(model.p95()).isEqualTo("1.0s");
            assertThat(model.timeoutRate()).isEqualTo(0.0);
            assertThat(model.costCny()).isEqualTo(0.0);
            assertThat(model.status()).isEqualTo("ok");
        });
        assertThat(result.stats()).anySatisfy(model -> {
            assertThat(model.name()).isEqualTo("deepseek-v3");
            assertThat(model.role()).isEqualTo("降级备选");
            assertThat(model.calls()).isEqualTo(1);
            assertThat(model.timeoutRate()).isEqualTo(1.0);
            assertThat(model.status()).isEqualTo("bad");
        });
        server.stop(0);
    }

    @Test void budgetUpdateIsAuditedAndStaysInsideTheSlider() throws Exception {
        var paths = new ArrayList<String>();
        var audited = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().endsWith("/missing-dev/budget")) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var costs = new CostService(new LiteLlmClient(base, "admin"), new LangfuseClient("", "", ""),
                (agent, env) -> audited.set(agent + ":" + env));
        costs.updateBudget("askdb-dev", new BigDecimal("45"));
        assertThat(audited.get()).isEqualTo("askdb:dev");
        assertThat(paths).contains("POST /admin/v1/keys/askdb-dev/budget");
        assertThatThrownBy(() -> costs.updateBudget("askdb-dev", new BigDecimal("12")))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
        assertThatThrownBy(() -> costs.updateBudget("missing-dev", new BigDecimal("30")))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_NOT_FOUND);
        server.stop(0);
    }

    private static String page(int count, String alias, String model, double cost) {
        var rows = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"alias\":\"").append(alias).append("\",\"model\":\"").append(model).append("\",\"costCny\":").append(cost).append('}');
        }
        return rows.append("]}").toString();
    }
}
