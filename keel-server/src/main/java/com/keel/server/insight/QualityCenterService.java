package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** User feedback from Langfuse scores, and one-click copies of downvotes into the eval dataset. */
@Service
public class QualityCenterService {
    private final LangfuseClient langfuse;
    private final Function<String, String> datasetOf;
    private final JdbcTemplate jdbc;

    @Autowired
    public QualityCenterService(LangfuseClient langfuse, AgentRegistryService registry, JdbcTemplate jdbc) {
        this(langfuse, agent -> dataset(registry, agent), jdbc);
    }

    public QualityCenterService(LangfuseClient langfuse, AgentRegistryService registry) {
        this(langfuse, agent -> dataset(registry, agent), null);
    }

    QualityCenterService(LangfuseClient langfuse, Function<String, String> datasetOf) {
        this(langfuse, datasetOf, null);
    }

    QualityCenterService(LangfuseClient langfuse, Function<String, String> datasetOf, JdbcTemplate jdbc) {
        this.langfuse = langfuse;
        this.datasetOf = datasetOf;
        this.jdbc = jdbc;
    }

    public Report report(String agent) {
        if (!langfuse.configured() && jdbc == null) {
            return new Report(false, 0, 0, null, List.of());
        }
        var filter = agent == null ? "" : agent.trim();
        int feedback = 0;
        int down = 0;
        var cases = new ArrayList<Case>();
        var added = addedTraces();
        for (var row : feedbackRows()) {
            var owner = agentOf(row);
            if (!filter.isBlank() && !filter.equals(owner)) {
                continue;
            }
            feedback++;
            if (!downvote(row)) {
                continue;
            }
            down++;
            if (cases.size() >= 50) {
                continue;
            }
            var traceId = row.path("traceId").asText("");
            var io = traceId.isBlank() ? null : ioOf(traceId);
            cases.add(new Case(traceId, owner, io == null ? "" : io[0], io == null ? "" : io[1],
                    row.path("comment").asText(""), row.path("createdAt").asText(""),
                    added.contains(traceId)));
        }
        Double rate = feedback == 0 ? null : Math.round(down * 1000.0 / feedback) / 1000.0;
        return new Report(true, feedback, down, rate, List.copyOf(cases));
    }

    public Added add(String agent, String traceId) {
        if (agent == null || agent.isBlank() || traceId == null || traceId.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "agent 和 traceId 不能为空");
        }
        var match = feedbackRows().stream()
                .filter(row -> traceId.equals(row.path("traceId").asText()) && downvote(row) && agent.equals(agentOf(row)))
                .findFirst()
                .orElseThrow(() -> new KeelException(ErrorCode.SERVER_INVALID_PARAM, "这不是该智能体的点踩调用"));
        var dataset = datasetOf.apply(agent);
        if (dataset == null || dataset.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "智能体没有评测集");
        }
        var io = ioOf(traceId);
        var input = io == null || io[0].isBlank() ? match.path("comment").asText(traceId) : io[0];
        var output = io == null ? "" : io[1];
        try {
            var itemId = langfuse.addFeedbackItem(dataset, traceId, input, output);
            return new Added(dataset, itemId, traceId);
        } catch (IllegalStateException e) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "评测集写入失败");
        }
    }

    private List<JsonNode> feedbackRows() {
        var rows = new ArrayList<JsonNode>();
        if (langfuse.configured()) langfuse.scores().path("data").forEach(row -> {
            if ("user-feedback".equals(row.path("name").asText())) rows.add(row);
        });
        if (jdbc != null) {
            rows.addAll(jdbc.query("SELECT agent_name,trace_id,value,created_at FROM channel_feedback ORDER BY created_at DESC LIMIT 1000",
                    (rs, n) -> {
                        var row = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                        row.put("name", "user-feedback");
                        row.put("traceId", rs.getString("trace_id"));
                        row.put("value", rs.getInt("value"));
                        row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                        row.putObject("metadata").put("keel.agent", rs.getString("agent_name"));
                        return row;
                    }));
        }
        return rows;
    }

    private Set<String> addedTraces() {
        var found = new HashSet<String>();
        var datasets = new HashMap<String, JsonNode>();
        for (var row : feedbackRows()) {
            if (!downvote(row)) {
                continue;
            }
            var dataset = datasetOf.apply(agentOf(row));
            if (dataset == null || dataset.isBlank()) {
                continue;
            }
            var items = datasets.computeIfAbsent(dataset, langfuse::datasetItems);
            items.path("data").forEach(item -> {
                var traceId = item.path("metadata").path("traceId").asText("");
                if (!traceId.isBlank()) {
                    found.add(traceId);
                }
            });
        }
        return found;
    }

    private String[] ioOf(String traceId) {
        try {
            JsonNode chosen = null;
            for (var row : langfuse.observationsByTrace(traceId).path("data")) {
                if (chosen == null) {
                    chosen = row;
                }
                if ("GENERATION".equals(row.path("type").asText())) {
                    chosen = row;
                    break;
                }
            }
            if (chosen == null) {
                return null;
            }
            return new String[] {text(chosen.path("input")), text(chosen.path("output"))};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String dataset(AgentRegistryService registry, String agent) {
        var manifest = registry.manifestOrEmpty(agent);
        if (manifest == null) {
            return "";
        }
        return manifest.path("spec").path("eval").path("dataset").asText("");
    }

    private static boolean downvote(JsonNode row) {
        return row.path("value").asDouble(0) < 0;
    }

    private static String agentOf(JsonNode row) {
        var metadata = row.path("metadata");
        var agent = metadata.path("keel.agent").asText("");
        return agent.isBlank() ? metadata.path("agent").asText("") : agent;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    public record Report(boolean connected, int feedbackCount, int downCount, Double downRate, List<Case> cases) {}

    public record Case(String traceId, String agent, String input, String output, String comment, String createdAt,
                       boolean added) {}

    public record Added(String dataset, String itemId, String traceId) {}
}
