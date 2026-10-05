package com.keel.server.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.integration.prometheus.PrometheusClient;
import com.keel.server.registry.service.AgentRegistryService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(result.models()).anySatisfy(model -> {
            if ("draft".equals(model.name())) {
                assertThat(model.priceConfigured()).isFalse();
            }
        });
        var registry = new AgentRegistryService(null, null, null, new ObjectMapper()) {
            @Override public List<com.keel.server.registry.model.dto.AgentSummary> listAll() { return List.of(); }
        };
        var overview = new OverviewService(registry, costs, new QualityService(langfuse), () -> 0).overview("all", "24h");
        assertThat(overview.kpi().modelCostCny()).isEqualTo(53.0);
        assertThat(overview.kpi().calls()).isEqualTo(51L);
        assertThat(overview.kpi().avgScore()).isEqualTo(0.9);
        assertThat(overview.kpi().pendingApprovals()).isZero();
        assertThat(overview.costByAgent().stream().mapToDouble(row -> (Double) row.get("costCny")).sum()).isEqualTo(53.0);
        assertThat(paths).noneMatch(path -> path.contains("/spend/logs") || path.contains("/key/generate")
                || path.equals("/api/public/metrics") || path.equals("/api/public/scores") || path.equals("/api/public/traces"));
        var monitor = new SharedServiceMonitor(new PrometheusClient("http://127.0.0.1:1"));
        @SuppressWarnings("unchecked")
        var service = ((List<java.util.Map<String, Object>>) monitor.services().get("services")).get(0);
        assertThat(service.get("p95")).isNull();
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
