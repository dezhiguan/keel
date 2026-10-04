package com.keel.server.insight;

import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class QualityService {
    private final LangfuseClient langfuse;

    public QualityService(LangfuseClient langfuse) {
        this.langfuse = langfuse;
    }

    public Double average() {
        var scores = byAgent();
        if (scores.isEmpty()) {
            return null;
        }
        return scores.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    /** Kept for the quality page in P2-12. */
    public Map<String, Double> byAgent() {
        try {
            var totals = new LinkedHashMap<String, double[]>();
            langfuse.scores().path("data").forEach(row -> {
                var agent = row.path("metadata").path("keel.agent").asText(row.path("name").asText(""));
                var bucket = totals.computeIfAbsent(agent, key -> new double[2]);
                bucket[0] += row.path("value").asDouble();
                bucket[1] += 1;
            });
            var averages = new LinkedHashMap<String, Double>();
            totals.forEach((agent, bucket) -> averages.put(agent, bucket[1] == 0 ? null : bucket[0] / bucket[1]));
            return averages;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
