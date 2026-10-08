package com.keel.server.devflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class JdbcHoldoutStore implements HoldoutStore {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final TypeReference<List<String>> TAGS = new TypeReference<>() {};
    private static final TypeReference<List<HoldoutRules.TagScore>> SCORES = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcHoldoutStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void replace(String jobId, List<DevflowTypes.SeedCase> cases, String actor) {
        jdbc.update("DELETE FROM devflow_holdout WHERE job_id = ?", jobId);
        for (var item : cases) {
            jdbc.update("""
                    INSERT INTO devflow_holdout (job_id, case_id, input_json, expected_json, tags, created_by)
                    VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                    """, jobId, item.caseId(), write(item.input()), write(item.expected()), write(item.tags()), actor);
        }
    }

    @Override
    public List<DevflowTypes.SeedCase> cases(String jobId) {
        return jdbc.query("""
                SELECT case_id, input_json::text AS input_json, expected_json::text AS expected_json, tags::text AS tags
                FROM devflow_holdout WHERE job_id = ? ORDER BY case_id
                """, (rs, row) -> new DevflowTypes.SeedCase(rs.getString("case_id"), read(rs.getString("input_json"), OBJECT),
                read(rs.getString("expected_json"), OBJECT), read(rs.getString("tags"), TAGS)), jobId);
    }

    @Override
    public void saveResult(String jobId, boolean passed, double score, List<HoldoutRules.TagScore> byTag) {
        jdbc.update("""
                INSERT INTO devflow_holdout_result (job_id, passed, score, by_tag)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (job_id) DO UPDATE SET passed = EXCLUDED.passed, score = EXCLUDED.score,
                    by_tag = EXCLUDED.by_tag, created_at = now()
                """, jobId, passed, score, write(byTag));
    }

    @Override
    public void clearResult(String jobId) {
        jdbc.update("DELETE FROM devflow_holdout_result WHERE job_id = ?", jobId);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
