package com.keel.server.devflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Gate rows for the eval page. Scores only; hidden case text stays in the holdout table. */
@Service
public class GateBoard {
    static final double PASS_AT = 0.80;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public GateBoard(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Map<String, Object>> rows() {
        return jdbc.query("""
                SELECT j.job_id, j.target_agent, e.state_json::text AS state_json, r.score
                FROM devflow_job j
                LEFT JOIN devflow_execution e ON e.job_id = j.job_id
                LEFT JOIN devflow_holdout_result r ON r.job_id = j.job_id
                WHERE j.stage IN ('GATE', 'H4', 'RELEASE', 'WATCH') OR j.status = 'DONE'
                ORDER BY j.job_id DESC
                """, (rs, row) -> {
            var score = rs.getBigDecimal("score");
            return jobRow(rs.getString("target_agent"), rs.getString("job_id"),
                    read(json, rs.getString("state_json")), score == null ? null : score.doubleValue());
        });
    }

    /** Prefer the prod gate. A missing score stays null. */
    static Map<String, Object> jobRow(String agent, String jobId, JsonNode state, Double holdoutResult) {
        var gate = state == null ? null : firstScore(state.path("prodGate"), state.path("gate"));
        Double visible = gate == null ? null : number(gate, "visibleScore");
        Double hidden = gate == null ? null : number(gate, "hiddenScore");
        if (hidden == null) {
            hidden = holdoutResult;
        }
        return row(agent, jobId, visible, hidden);
    }

    public static List<Map<String, Object>> assemble(List<Map<String, Object>> jobs, Set<String> envAgents,
                                               Set<String> producedElsewhere, Map<String, Double> evalScores) {
        var rows = new ArrayList<Map<String, Object>>();
        var produced = new LinkedHashSet<String>();
        if (producedElsewhere != null) {
            produced.addAll(producedElsewhere);
        }
        for (var job : jobs) {
            var agent = String.valueOf(job.get("agent"));
            if (envAgents != null && !envAgents.contains(agent)) {
                continue;
            }
            rows.add(job);
            produced.add(agent);
        }
        if (evalScores == null) {
            return rows;
        }
        for (var entry : evalScores.entrySet()) {
            if (produced.contains(entry.getKey())) {
                continue;
            }
            if (envAgents != null && !envAgents.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            rows.add(row(entry.getKey(), null, entry.getValue(), null));
        }
        return rows;
    }

    private static Map<String, Object> row(String agent, String jobId, Double visible, Double holdout) {
        var row = new LinkedHashMap<String, Object>();
        row.put("agent", agent);
        row.put("jobId", jobId);
        row.put("visible", visible);
        row.put("holdout", jobId == null ? null : holdout);
        row.put("passed", visible == null ? null : rounded(visible) >= PASS_AT);
        return row;
    }

    private static JsonNode firstScore(JsonNode prod, JsonNode staging) {
        if (prod != null && prod.has("visibleScore")) {
            return prod;
        }
        if (staging != null && staging.has("visibleScore")) {
            return staging;
        }
        return null;
    }

    private static Double number(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.asDouble();
    }

    private static double rounded(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static JsonNode read(ObjectMapper json, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
