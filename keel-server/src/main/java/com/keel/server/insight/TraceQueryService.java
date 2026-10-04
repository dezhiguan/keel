package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class TraceQueryService {
    private final LangfuseClient langfuse;
    private final String host;
    private final String projectId;
    private final Map<String, double[]> pricesCnyPerToken;
    private final SavedTraces saved;

    @Autowired
    public TraceQueryService(LangfuseClient langfuse,
                             @Value("${LANGFUSE_HOST:}") String host,
                             @Value("${LANGFUSE_PROJECT_ID:}") String projectId,
                             SavedTraces saved) {
        this(langfuse, host, projectId, Map.of(), saved);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken) {
        this(langfuse, host, projectId, pricesCnyPerToken, SavedTraces.EMPTY);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken,
                             SavedTraces saved) {
        this.langfuse = langfuse;
        this.host = host == null ? "" : host.replaceAll("/$", "");
        this.projectId = projectId == null ? "" : projectId;
        this.pricesCnyPerToken = pricesCnyPerToken;
        this.saved = saved == null ? SavedTraces.EMPTY : saved;
    }

    public Map<String, Object> list(int page, int size) {
        return list(page, size, "");
    }

    public Map<String, Object> list(int page, int size, String agent) {
        try {
            var remote = remoteList(page, size);
            @SuppressWarnings("unchecked")
            var items = (java.util.List<?>) remote.get("items");
            if (items != null && !items.isEmpty()) {
                return remote;
            }
        } catch (RuntimeException ignored) {
            // Langfuse 没配好或读失败时，改看本机探针写下的 trace。
        }
        return saved.list(page, size, agent);
    }

    private Map<String, Object> remoteList(int page, int size) {
        JsonNode body = langfuse.observationsPage();
        var byTrace = new LinkedHashMap<String, List<JsonNode>>();
        body.path("data").forEach(row -> byTrace.computeIfAbsent(row.path("traceId").asText(""), key -> new ArrayList<>()).add(row));
        var ids = new ArrayList<>(byTrace.keySet());
        int from = Math.max(0, (page - 1) * size);
        var items = new ArrayList<Map<String, Object>>();
        for (int i = from; i < Math.min(ids.size(), from + size); i++) {
            items.add(summary(ids.get(i), byTrace.get(ids.get(i))));
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("page", page);
        data.put("size", size);
        data.put("total", ids.size());
        data.put("items", items);
        return data;
    }

    public Map<String, Object> detail(String traceId) {
        try {
            return remoteDetail(traceId);
        } catch (RuntimeException e) {
            var local = saved.detail(traceId);
            if (local != null) {
                return local;
            }
            throw e;
        }
    }

    private Map<String, Object> remoteDetail(String traceId) {
        JsonNode body = langfuse.observationsByTrace(traceId);
        var rows = new ArrayList<JsonNode>();
        body.path("data").forEach(rows::add);
        if (rows.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        var nodes = new ArrayList<Map<String, Object>>();
        var edges = new ArrayList<Map<String, Object>>();
        long start = rows.stream().mapToLong(row -> Instant.parse(row.path("startTime").asText()).toEpochMilli()).min().orElse(0);
        var agents = new LinkedHashSet<String>();
        int leafMs = 0;
        for (JsonNode row : rows) {
            String id = row.path("id").asText();
            String parent = row.path("parentObservationId").asText("");
            long nodeStart = Instant.parse(row.path("startTime").asText()).toEpochMilli() - start;
            long nodeEnd = Instant.parse(row.path("endTime").asText()).toEpochMilli() - start;
            int duration = (int) Math.max(0, nodeEnd - nodeStart);
            String agent = metadata(row, "keel.agent");
            if (!agent.isBlank()) {
                agents.add(agent);
            }
            boolean leaf = rows.stream().noneMatch(other -> id.equals(other.path("parentObservationId").asText()));
            if (leaf) {
                leafMs += duration;
            }
            var node = new LinkedHashMap<String, Object>();
            node.put("id", id);
            node.put("agentKey", agent);
            node.put("type", metadata(row, "langfuse.observation.type"));
            node.put("name", row.path("name").asText());
            node.put("model", row.path("metadata").path("gen_ai.request.model").asText(null));
            node.put("startMs", (int) nodeStart);
            node.put("durationMs", duration);
            node.put("inputSummary", row.path("name").asText());
            node.put("outputSummary", row.path("name").asText());
            node.put("auditIds", auditIds(row));
            node.put("approvalId", null);
            node.put("humanWaitLabel", null);
            node.put("costCny", cost(row));
            nodes.add(node);
            if (!parent.isBlank()) {
                edges.add(Map.of("from", parent, "to", id));
            }
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("traceId", traceId);
        summary.put("rootAgent", agents.isEmpty() ? "" : agents.getFirst());
        summary.put("agents", new ArrayList<>(agents));
        summary.put("multiAgent", agents.size() > 1);
        summary.put("durationMs", leafMs);
        var costs = nodes.stream().map(node -> node.get("costCny")).filter(value -> value instanceof Number).map(value -> ((Number) value).doubleValue()).toList();
        summary.put("costCny", costs.isEmpty() ? null : costs.stream().mapToDouble(Double::doubleValue).sum());
        var detail = new LinkedHashMap<String, Object>();
        detail.put("summary", summary);
        detail.put("langfuseUrl", host + "/project/" + projectId + "/traces/" + traceId);
        detail.put("latencyBreakdown", List.of(Map.of("label", "leaves", "ms", leafMs)));
        detail.put("nodes", nodes);
        detail.put("edges", edges);
        return detail;
    }

    private List<String> auditIds(JsonNode row) {
        var ids = new ArrayList<String>();
        row.path("metadata").path("keel.audit_ids").forEach(item -> ids.add(item.asText()));
        return ids;
    }

    private Double cost(JsonNode row) {
        String model = metadata(row, "gen_ai.request.model");
        if (model.isBlank()) {
            model = row.path("metadata").path("gen_ai.request.model").asText("");
        }
        double[] price = pricesCnyPerToken.get(model);
        int in = row.path("metadata").path("gen_ai.usage.input_tokens").asInt(0);
        int out = row.path("metadata").path("gen_ai.usage.output_tokens").asInt(0);
        if (price == null || (in == 0 && out == 0)) {
            return null;
        }
        return in * price[0] + out * price[1];
    }

    private static String metadata(JsonNode row, String key) {
        return row.path("metadata").path(key).asText("");
    }

    private Map<String, Object> summary(String traceId, List<JsonNode> rows) {
        var agents = new LinkedHashSet<String>();
        rows.forEach(row -> {
            String agent = metadata(row, "keel.agent");
            if (!agent.isBlank()) {
                agents.add(agent);
            }
        });
        var item = new LinkedHashMap<String, Object>();
        item.put("traceId", traceId);
        item.put("agents", new ArrayList<>(agents));
        item.put("multiAgent", agents.size() > 1);
        return item;
    }
}
