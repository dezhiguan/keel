package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
public class EvalQueryService {
    private final LangfuseClient langfuse;
    private final Function<String, JsonNode> manifest;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    @Autowired
    public EvalQueryService(LangfuseClient langfuse, AgentRegistryService registry) {
        this(langfuse, registry::manifestOrEmpty);
    }

    EvalQueryService(LangfuseClient langfuse, Function<String, JsonNode> manifest) {
        this.langfuse = langfuse;
        this.manifest = manifest;
    }

    public Map<String, Object> latest(String agent) {
        var gate = gate(agent);
        var dataset = gate.dataset();
        JsonNode experiments;
        try {
            experiments = langfuse.experiments();
        } catch (RuntimeException e) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "该智能体还没有评测记录");
        }
        JsonNode chosen = null;
        String chosenAt = "";
        for (var row : experiments.path("data")) {
            if (!matches(row, agent, dataset)) {
                continue;
            }
            var at = time(row);
            if (chosen == null || at.compareTo(chosenAt) > 0) {
                chosen = row;
                chosenAt = at;
            }
        }
        if (chosen == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "该智能体还没有评测记录");
        }
        var items = items(chosen);
        return result(agent, dataset, gate, chosen, items);
    }

    /** Latest experiment score per agent, the same number the eval page shows. Missing agents are omitted. */
    public Map<String, Double> latestScoreByAgent() {
        JsonNode experiments;
        try {
            experiments = langfuse.experiments();
        } catch (RuntimeException e) {
            return Map.of();
        }
        var chosen = new LinkedHashMap<String, JsonNode>();
        var chosenAt = new LinkedHashMap<String, String>();
        for (var row : experiments.path("data")) {
            var agent = agentOf(row);
            if (agent == null) {
                continue;
            }
            var at = time(row);
            if (!chosen.containsKey(agent) || at.compareTo(chosenAt.getOrDefault(agent, "")) > 0) {
                chosen.put(agent, row);
                chosenAt.put(agent, at);
            }
        }
        var scores = new LinkedHashMap<String, Double>();
        chosen.forEach((agent, row) -> {
            var gate = gate(agent);
            var score = result(agent, gate.dataset(), gate, row, items(row)).get("scoreTotal");
            if (score instanceof Number number) {
                scores.put(agent, number.doubleValue());
            }
        });
        return scores;
    }

    public String start(String agent) {
        var id = "ev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var run = new Run();
        runs.put(id, run);
        try {
            run.result = latest(agent);
            run.state = "DONE";
            run.progress = 1;
        } catch (KeelException e) {
            run.state = "FAILED";
            run.progress = 1;
        }
        return id;
    }

    public Map<String, Object> run(String runId) {
        var run = runs.get(runId);
        if (run == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("runId", runId);
        body.put("state", run.state);
        body.put("progress", run.progress);
        body.put("result", run.result);
        return body;
    }

    private JsonNode items(JsonNode experiment) {
        var id = text(experiment, "id", "experimentId");
        if (id.isBlank()) {
            return null;
        }
        try {
            return langfuse.experimentItems(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> result(String agent, String dataset, Gate gate, JsonNode experiment, JsonNode items) {
        var buckets = new LinkedHashMap<String, double[]>();
        collect(scores(experiment), buckets, true);
        int cases = 0;
        if (items != null) {
            for (var item : items.path("data")) {
                cases++;
                collect(scores(item), buckets, false);
            }
        }
        fillCandidateFromExperiment(buckets);
        var dimensions = new ArrayList<Map<String, Object>>();
        double total = 0;
        int counted = 0;
        String exceeded = null;
        for (var entry : buckets.entrySet()) {
            var bucket = entry.getValue();
            if (bucket[1] == 0) {
                continue;
            }
            double candidate = bucket[0] / bucket[1];
            Double prod = bucket[3] == 0 ? null : bucket[2] / bucket[3];
            Integer delta = prod == null ? null : (int) Math.round((candidate - prod) * 100);
            String verdict = "TOLERATED";
            if (delta != null && delta >= 0) {
                verdict = "IMPROVED";
            }
            if (delta != null && delta < -gate.maxRegressionPt()) {
                verdict = "EXCEEDED";
                exceeded = entry.getKey();
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("tag", entry.getKey());
            row.put("cases", (int) bucket[4]);
            row.put("prodScore", prod);
            row.put("candidateScore", candidate);
            row.put("deltaPt", delta);
            row.put("verdict", verdict);
            dimensions.add(row);
            total += candidate;
            counted++;
        }
        Double score = counted == 0 ? null : total / counted;
        boolean passed = score != null && score >= gate.minScore() && exceeded == null;
        var body = new LinkedHashMap<String, Object>();
        body.put("agent", agent);
        body.put("dataset", dataset);
        body.put("caseCount", cases == 0 ? counted : cases);
        body.put("prodVersion", text(experiment, "prodVersion", "baselineVersion"));
        body.put("candidateVersion", text(experiment, "name", "candidateVersion"));
        body.put("scoreTotal", score);
        body.put("passed", passed);
        body.put("gate", Map.of("minScore", gate.minScore(), "maxRegression", gate.maxRegressionPt(), "byTag", true));
        body.put("reason", exceeded == null ? null : "「" + exceeded + "」退步超过 " + gate.maxRegressionPt() + "pt");
        body.put("ranAt", time(experiment).isBlank() ? Instant.now().toString() : time(experiment));
        body.put("dimensions", dimensions);
        body.put("newFailures", List.of());
        return body;
    }

    private static void collect(JsonNode scores, Map<String, double[]> buckets, boolean experimentLevel) {
        if (scores == null || !scores.isArray()) {
            return;
        }
        scores.forEach(score -> {
            var name = score.path("name").asText("");
            if (name.isBlank()) {
                return;
            }
            var value = score.path("value");
            if (!value.isNumber()) {
                return;
            }
            var bucket = buckets.computeIfAbsent(name, key -> new double[5]);
            if (experimentLevel) {
                bucket[2] += value.asDouble();
                bucket[3] += 1;
            } else {
                bucket[0] += value.asDouble();
                bucket[1] += 1;
                bucket[4] += 1;
            }
        });
    }

    private static void fillCandidateFromExperiment(Map<String, double[]> buckets) {
        buckets.values().forEach(bucket -> {
            if (bucket[1] == 0 && bucket[3] > 0) {
                bucket[0] = bucket[2];
                bucket[1] = bucket[3];
            }
        });
    }

    private static JsonNode scores(JsonNode row) {
        if (row == null) {
            return null;
        }
        if (row.path("scores").isArray()) {
            return row.path("scores");
        }
        return row.path("experimentScores");
    }

    private static String agentOf(JsonNode row) {
        var dataset = text(row, "datasetName", "dataset");
        var slash = dataset.indexOf('/');
        if (slash > 0) {
            return dataset.substring(0, slash);
        }
        var metadata = row.path("metadata");
        var metaAgent = metadata.path("agent").asText(metadata.path("keel.agent").asText(""));
        return metaAgent.isBlank() ? null : metaAgent;
    }

    private static boolean matches(JsonNode row, String agent, String dataset) {
        var name = text(row, "datasetName", "dataset");
        if (!name.isBlank()) {
            return name.equals(dataset) || name.startsWith(agent + "/");
        }
        var metadata = row.path("metadata");
        var metaAgent = metadata.path("agent").asText(metadata.path("keel.agent").asText(""));
        return metaAgent.equals(agent) || text(row, "name", "experimentName").startsWith(agent);
    }

    private static String time(JsonNode row) {
        for (var field : List.of("createdAt", "startTime", "timestamp", "ranAt")) {
            var value = row.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode row, String first, String second) {
        var value = row.path(first).asText("");
        if (!value.isBlank()) {
            return value;
        }
        var metadata = row.path("metadata").path(first).asText("");
        if (!metadata.isBlank()) {
            return metadata;
        }
        return row.path(second).asText(row.path("metadata").path(second).asText(""));
    }

    private Gate gate(String agent) {
        JsonNode manifestNode = null;
        try {
            manifestNode = manifest.apply(agent);
        } catch (RuntimeException ignored) {
            manifestNode = null;
        }
        var eval = manifestNode == null ? null : manifestNode.path("spec").path("eval");
        var dataset = eval == null ? "" : eval.path("dataset").asText("");
        if (dataset.isBlank()) {
            dataset = agent + "/smoke";
        }
        double min = eval == null ? 0.85 : eval.path("gate").path("minScore").asDouble(0.85);
        double regression = eval == null ? 2 : eval.path("gate").path("maxRegression").asDouble(2);
        if (regression > 0 && regression <= 1) {
            regression = regression * 100;
        }
        return new Gate(dataset, min, regression);
    }

    private record Gate(String dataset, double minScore, double maxRegressionPt) {}

    private static final class Run {
        private String state = "RUNNING";
        private double progress;
        private Map<String, Object> result;
    }
}
