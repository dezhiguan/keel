package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.AgentVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 版本行上的评测分和门禁只来自该版本关联的评测或发布记录。 */
@Component
public class VersionEvidence {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public VersionEvidence(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<AgentDetail.VersionRow> attach(String agent, List<AgentVersion> versions) {
        if (versions == null || versions.isEmpty()) return List.of();
        var releases = releases(agent);
        var runIds = new LinkedHashSet<String>();
        for (var version : versions) {
            if (isEvalRun(version.getGateRunId())) runIds.add(version.getGateRunId());
            var release = releases.get(key(version.getVersion(), version.getEnv()));
            if (release != null && isEvalRun(release.runId())) runIds.add(release.runId());
        }
        return rows(versions, evals(agent, runIds), releases);
    }

    static List<AgentDetail.VersionRow> rows(List<AgentVersion> versions, Map<String, EvalResult> evals,
                                              Map<String, ReleaseResult> releases) {
        var out = new ArrayList<AgentDetail.VersionRow>();
        for (var version : versions) {
            var release = releases.get(key(version.getVersion(), version.getEnv()));
            EvalResult eval = null;
            if (isEvalRun(version.getGateRunId())) eval = evals.get(version.getGateRunId());
            if (eval == null && release != null && isEvalRun(release.runId())) eval = evals.get(release.runId());
            Boolean gate = eval != null && eval.passed() != null ? eval.passed() : release == null ? null : release.gatePassed();
            Double score = eval != null && eval.score() != null ? eval.score() : release == null ? null : release.score();
            out.add(AgentDetail.VersionRow.of(version).withEvaluation(gate, score));
        }
        return out;
    }

    private Map<String, ReleaseResult> releases(String agent) {
        var out = new LinkedHashMap<String, ReleaseResult>();
        jdbc.query("""
                SELECT version, env, gate_passed, score_total, ci_run_url
                FROM release_record
                WHERE agent_name = ? AND kind = 'release'
                ORDER BY created_at DESC, id DESC
                """, rs -> {
            var version = rs.getString(1);
            var env = rs.getString(2);
            out.putIfAbsent(key(version, env), new ReleaseResult(rs.getBoolean(3), score(rs.getBigDecimal(4)), rs.getString(5)));
        }, agent);
        return out;
    }

    private Map<String, EvalResult> evals(String agent, Iterable<String> runIds) {
        var ids = new ArrayList<String>();
        runIds.forEach(ids::add);
        if (ids.isEmpty()) return Map.of();
        var marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var args = new ArrayList<Object>();
        args.add(agent);
        args.addAll(ids);
        var out = new LinkedHashMap<String, EvalResult>();
        jdbc.query("""
                SELECT run_id, result::text FROM eval_run
                WHERE agent_name = ? AND state = 'DONE' AND run_id IN (%s)
                """.formatted(marks), rs -> {
            var parsed = parse(rs.getString(2));
            if (parsed != null) out.put(rs.getString(1), parsed);
        }, args.toArray());
        return out;
    }

    private EvalResult parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode node = json.readTree(text);
            Double score = node.path("scoreTotal").isNumber() ? node.path("scoreTotal").asDouble() : null;
            Boolean passed = node.path("passed").isBoolean() ? node.path("passed").asBoolean() : null;
            if (score != null && !Double.isFinite(score)) score = null;
            return score == null && passed == null ? null : new EvalResult(score, passed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Double score(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    static boolean isEvalRun(String runId) {
        return runId != null && runId.startsWith("ev_");
    }

    private static String key(String version, String env) {
        return version + "\n" + env;
    }

    record EvalResult(Double score, Boolean passed) {}

    record ReleaseResult(Boolean gatePassed, Double score, String runId) {}
}
