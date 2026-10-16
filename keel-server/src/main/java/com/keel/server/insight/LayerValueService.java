package com.keel.server.insight;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Reads ledger tables the overview already owns. It does not call Langfuse. */
@Service
public class LayerValueService {
    private final JdbcTemplate jdbc;

    public LayerValueService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LayerValue.Layers compute(String env, List<String> bizAgents, List<String> devAgents) {
        var jobs = jobs();
        return new LayerValue.Layers(
                biz(env, bizAgents),
                LayerValue.dev(jobs),
                LayerValue.meta(jobs, scores(devAgents), humanCode(), codeArtifacts()));
    }

    private LayerValue.Biz biz(String env, List<String> agents) {
        if (agents == null || agents.isEmpty()) return new LayerValue.Biz(null, null, null, null);
        var feedback = counts("""
                SELECT count(*), count(*) FILTER (WHERE value < 0)
                FROM channel_feedback WHERE agent_name IN (""" + marks(agents.size()) + ")", agents);
        var runs = runCounts(env, agents);
        return LayerValue.biz(runs[0], runs[1], runs[2], runs[3], feedback[0], feedback[1]);
    }

    private int[] runCounts(String env, List<String> agents) {
        var args = new ArrayList<Object>(agents);
        var sql = """
                SELECT count(*) FILTER (WHERE status IN ('DONE', 'FAILED', 'EXPIRED')),
                       count(*) FILTER (WHERE status = 'DONE' AND (suspend_reason IS NULL OR suspend_reason <> 'handoff')),
                       count(*),
                       count(*) FILTER (WHERE suspend_reason = 'handoff')
                FROM agent_run WHERE agent_name IN (""" + marks(agents.size()) + ")";
        if (env != null && !env.isBlank() && !"all".equals(env)) {
            sql += " AND env = ?";
            args.add(env);
        }
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return new int[] {0, 0, 0, 0};
            return new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)};
        }, args.toArray());
    }

    private int[] counts(String sql, List<String> agents) {
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return new int[] {0, 0};
            return new int[] {rs.getInt(1), rs.getInt(2)};
        }, agents.toArray());
    }

    private List<LayerValue.JobSample> jobs() {
        return jdbc.query("""
                SELECT j.status, j.stage, j.layer, j.kind, j.fix_rounds, j.human_dev_user,
                       h.passed, EXTRACT(EPOCH FROM (j.updated_at - j.created_at)) / 86400.0
                FROM devflow_job j
                LEFT JOIN devflow_holdout_result h ON h.job_id = j.job_id
                WHERE j.status <> 'CANCEL'
                """, (rs, n) -> {
            var passed = rs.getObject("passed") == null ? null : rs.getBoolean("passed");
            var human = rs.getString("human_dev_user");
            return new LayerValue.JobSample(rs.getString("status"), rs.getString("stage"), rs.getString("layer"),
                    rs.getString("kind"), rs.getInt("fix_rounds"), human != null && !human.isBlank(), passed,
                    rs.getObject(8) == null ? null : rs.getDouble(8));
        });
    }

    private List<Double> scores(List<String> agents) {
        if (agents == null || agents.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT DISTINCT ON (agent_name) (result->>'scoreTotal')::float8
                FROM eval_run
                WHERE state = 'DONE' AND result->>'scoreTotal' IS NOT NULL
                  AND agent_name IN (""" + marks(agents.size()) + """
                )
                ORDER BY agent_name, created_at DESC
                """, (rs, n) -> rs.getDouble(1), agents.toArray());
    }

    private int humanCode() {
        return count("""
                SELECT count(*) FROM devflow_artifact a
                JOIN devflow_job j ON j.job_id = a.job_id
                WHERE a.kind = 'CODE' AND j.layer = 'DEV' AND a.origin = 'HUMAN'
                """);
    }

    private int codeArtifacts() {
        return count("""
                SELECT count(*) FROM devflow_artifact a
                JOIN devflow_job j ON j.job_id = a.job_id
                WHERE a.kind = 'CODE' AND j.layer = 'DEV'
                """);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String marks(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }
}
