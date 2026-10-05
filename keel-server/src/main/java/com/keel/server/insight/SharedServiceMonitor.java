package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.integration.prometheus.PrometheusExposition;
import com.keel.server.integration.ragforge.RagForgeInsightClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SharedServiceMonitor {
    private final RagForgeInsightClient ragforge;
    private final LiteLlmClient gateway;

    public SharedServiceMonitor(RagForgeInsightClient ragforge, LiteLlmClient gateway) {
        this.ragforge = ragforge;
        this.gateway = gateway;
    }

    public Map<String, Object> services() {
        JsonNode insight = ragforge.configured() ? ragforge.insight() : null;
        var scrape = ragforge.configured() ? ragforge.prometheus() : new RagForgeInsightClient.Scrape(List.of(), 0);
        boolean up = ragforge.configured() && (insight != null || !scrape.bodies().isEmpty() || ragforge.up());
        var samples = new ArrayList<PrometheusExposition.Sample>();
        scrape.bodies().forEach(body -> samples.addAll(PrometheusExposition.parse(body)));

        var service = new LinkedHashMap<String, Object>();
        service.put("name", "rag-forge");
        service.put("role", "知识检索");
        service.put("instances", instances(scrape));
        Double p95Seconds = seconds(number(insight, "p95Ms"));
        service.put("p95", p95Seconds == null ? null : trim(p95Seconds) + "s");
        service.put("errorRate", null);
        service.put("status", status(up, scrape));

        var kpi = new LinkedHashMap<String, Object>();
        Long searches = insight == null ? null : longOrNull(insight.get("searches24h"));
        Long previous = insight == null ? null : longOrNull(insight.get("searchesPrev24h"));
        kpi.put("searches24h", searches);
        kpi.put("searchTrendPct", trend(searches, previous));
        kpi.put("p95Seconds", p95Seconds);
        kpi.put("p50Seconds", seconds(number(insight, "p50Ms")));
        kpi.put("throttleRate", null);
        var bases = insight == null ? List.<JsonNode>of() : children(insight.path("knowledgeBases"));
        kpi.put("kbCount", insight == null ? null : bases.size());
        kpi.put("staleKbCount", insight == null ? null : bases.stream().filter(row -> row.path("stale").asBoolean(false)).count());
        kpi.put("modelCostCny", cost(insight));

        var rag = new LinkedHashMap<String, Object>();
        rag.put("kpi", kpi);
        rag.put("stageLatency", PrometheusExposition.stageMeans(samples));
        rag.put("callers", PrometheusExposition.callers(samples));
        rag.put("knowledgeBases", bases.stream().map(SharedServiceMonitor::kb).toList());

        var body = new LinkedHashMap<String, Object>();
        body.put("services", List.of(service));
        body.put("ragforge", rag);
        return body;
    }

    private Double cost(JsonNode insight) {
        Double fromGateway = gatewayCost();
        if (fromGateway != null) {
            return fromGateway;
        }
        return number(insight, "modelCostCny");
    }

    /** Present only when a rag-forge virtual key exists. Zero spend then replaces the daily table. */
    private Double gatewayCost() {
        try {
            var keys = gateway.keys().stream().filter(key -> key.alias().startsWith("rag-forge-")).toList();
            if (keys.isEmpty()) {
                return null;
            }
            return keys.stream().mapToDouble(LiteLlmClient.VirtualKey::spentCny).sum();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String instances(RagForgeInsightClient.Scrape scrape) {
        if (scrape.targets() <= 0) {
            return null;
        }
        return scrape.bodies().size() + "/" + scrape.targets();
    }

    private static String status(boolean up, RagForgeInsightClient.Scrape scrape) {
        if (!up && scrape.targets() == 0 && scrape.bodies().isEmpty()) {
            return null;
        }
        if (scrape.targets() > 0 && scrape.bodies().size() < scrape.targets() && !scrape.bodies().isEmpty()) {
            return "DEGRADED";
        }
        return up ? "ONLINE" : "OFFLINE";
    }

    private static Double trend(Long current, Long previous) {
        if (current == null || previous == null) {
            return null;
        }
        if (previous == 0) {
            return current == 0 ? 0d : null;
        }
        return Math.round((current - previous) * 1000d / previous) / 10d;
    }

    private static Map<String, Object> kb(JsonNode row) {
        var kb = new LinkedHashMap<String, Object>();
        kb.put("kb", text(row, "kb"));
        kb.put("owner", text(row, "owner"));
        kb.put("documents", longOrNull(row.get("documents")));
        kb.put("updatedAt", text(row, "updatedAt"));
        kb.put("searches24h", longOrNull(row.get("searches24h")));
        kb.put("zeroHitRate", number(row, "zeroHitRate"));
        kb.put("recallAt5", number(row, "recallAt5"));
        kb.put("stale", row.path("stale").asBoolean(false));
        return kb;
    }

    private static List<JsonNode> children(JsonNode node) {
        var rows = new ArrayList<JsonNode>();
        if (node != null && node.isArray()) {
            node.forEach(rows::add);
        }
        return rows;
    }

    private static Double seconds(Double millis) {
        return millis == null ? null : Math.round(millis) / 1000d;
    }

    private static String trim(double seconds) {
        String text = Double.toString(seconds);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static Double number(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asDouble();
    }

    private static Long longOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
