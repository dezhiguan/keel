package com.keel.server.integration.prometheus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses a Prometheus text exposition. There is no Prometheus query server in this cluster. */
public final class PrometheusExposition {
    private PrometheusExposition() {}

    public record Sample(String name, Map<String, String> labels, double value) {}

    public static List<Sample> parse(String text) {
        var samples = new ArrayList<Sample>();
        if (text == null || text.isBlank()) {
            return samples;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int space = line.lastIndexOf(' ');
            if (space <= 0) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(line.substring(space + 1).trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            String head = line.substring(0, space).trim();
            int brace = head.indexOf('{');
            if (brace < 0) {
                samples.add(new Sample(head, Map.of(), value));
                continue;
            }
            int end = head.lastIndexOf('}');
            if (end < brace) {
                continue;
            }
            samples.add(new Sample(head.substring(0, brace), labels(head.substring(brace + 1, end)), value));
        }
        return samples;
    }

    /** Mean milliseconds of retrieval stages. {@code total} is the whole request and is left out. */
    public static List<Map<String, Object>> stageMeans(List<Sample> samples) {
        var sum = new LinkedHashMap<String, Double>();
        var count = new LinkedHashMap<String, Double>();
        for (Sample sample : samples) {
            String stage = sample.labels().get("stage");
            if (stage == null || stage.isBlank() || "total".equals(stage)) {
                continue;
            }
            if (sample.name().equals("ragforge_retrieval_latency_seconds_sum")) {
                sum.merge(stage, sample.value(), Double::sum);
            } else if (sample.name().equals("ragforge_retrieval_latency_seconds_count")) {
                count.merge(stage, sample.value(), Double::sum);
            }
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (String stage : List.of("rewrite", "vector", "keyword", "rerank", "other")) {
            Double n = count.get(stage);
            Double seconds = sum.get(stage);
            if (n == null || n <= 0 || seconds == null) {
                continue;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("stage", stage);
            row.put("meanMs", (int) Math.round(seconds / n * 1000));
            row.put("basis", "mean");
            rows.add(row);
        }
        return rows;
    }

    /** Calls grouped by caller_agent. Missing or unknown labels are skipped. */
    public static List<Map<String, Object>> callers(List<Sample> samples) {
        var calls = new LinkedHashMap<String, Double>();
        for (Sample sample : samples) {
            if (!sample.name().equals("ragforge_retrieval_requests_total")) {
                continue;
            }
            String agent = sample.labels().get("caller_agent");
            if (agent == null || agent.isBlank() || "unknown".equals(agent) || "none".equals(agent)) {
                continue;
            }
            calls.merge(agent, sample.value(), Double::sum);
        }
        var rows = new ArrayList<Map<String, Object>>();
        calls.forEach((agent, value) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("agent", agent);
            row.put("calls", (int) Math.round(value));
            rows.add(row);
        });
        return rows;
    }

    private static Map<String, String> labels(String text) {
        var labels = new LinkedHashMap<String, String>();
        for (String part : text.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = part.substring(eq + 1).trim();
            if (value.length() >= 2 && value.charAt(0) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            labels.put(part.substring(0, eq).trim(), value);
        }
        return labels;
    }
}
