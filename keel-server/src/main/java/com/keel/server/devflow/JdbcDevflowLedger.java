package com.keel.server.devflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class JdbcDevflowLedger implements DevflowLedger {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final TypeReference<List<DevflowTypes.ToolRef>> TOOLS = new TypeReference<>() {};
    private static final TypeReference<List<String>> NAMES = new TypeReference<>() {};
    private static final TypeReference<List<DevflowTypes.Event>> EVENTS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcDevflowLedger(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<DevflowTypes.Job> jobs() {
        return jdbc.query(selectJob() + " ORDER BY created_at DESC, job_id DESC", this::job);
    }

    @Override
    public void insert(DevflowTypes.Job job) {
        jdbc.update("""
                INSERT INTO devflow_job (
                    job_id, batch_id, layer, kind, mode, title, requester, owner_org, target_agent, producer_agent,
                    template, status, stage, human_dev_user, budget_cny, spent_cny, fix_rounds, max_fix_rounds,
                    need_review, watch_day, goal, tools_json, knowledge_json, seed_json, events_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                """, job.jobId(), job.batchId(), job.layer(), job.kind(), job.mode(), job.title(), job.requester(),
                job.ownerOrg(), job.targetAgent(), job.producerAgent(), job.template(), job.status(), job.stage(),
                job.humanDevUser(), job.budgetCny(), job.spentCny(), job.fixRounds(), job.maxFixRounds(),
                job.needReview(), job.watchDay(), job.goal(), write(job.tools()), write(job.knowledge()),
                write(job.seed()), write(job.events()));
    }

    @Override
    public void update(DevflowTypes.Job job) {
        jdbc.update("""
                UPDATE devflow_job
                SET status = ?, stage = ?, human_dev_user = ?, spent_cny = ?, fix_rounds = ?, need_review = ?,
                    seed_json = ?::jsonb, events_json = ?::jsonb, updated_at = now()
                WHERE job_id = ?
                """, job.status(), job.stage(), job.humanDevUser(), job.spentCny(), job.fixRounds(), job.needReview(),
                write(job.seed()), write(job.events()), job.jobId());
    }

    @Override
    public String nextJobId() {
        var next = jdbc.queryForObject("SELECT nextval('devflow_job_seq')", Long.class);
        return "DF-" + String.format("%04d", next == null ? 1 : next);
    }

    @Override
    public String nextBatchId() {
        var next = jdbc.queryForObject("SELECT nextval('devflow_batch_seq')", Long.class);
        return "B-" + String.format("%02d", next == null ? 1 : next);
    }

    @Override
    public List<DevflowTypes.Batch> batches() {
        return jdbc.query("""
                SELECT batch_id, title, requester, concurrency, pilot_job_id, pilot_passed, created_at
                FROM devflow_batch ORDER BY created_at DESC
                """, (rs, n) -> new DevflowTypes.Batch(rs.getString("batch_id"), rs.getString("title"),
                rs.getString("requester"), rs.getInt("concurrency"), rs.getString("pilot_job_id"),
                rs.getBoolean("pilot_passed"), day(rs.getTimestamp("created_at")), List.of()));
    }

    @Override
    public void insertBatch(DevflowTypes.Batch batch) {
        jdbc.update("""
                INSERT INTO devflow_batch (batch_id, title, requester, concurrency, pilot_job_id, pilot_passed)
                VALUES (?, ?, ?, ?, ?, ?)
                """, batch.batchId(), batch.title(), batch.requester(), batch.concurrency(), batch.pilotJobId(),
                batch.pilotPassed());
    }

    @Override
    public DevflowTypes.Settings settings() {
        var rows = jdbc.query("SELECT value_json::text AS value FROM devflow_setting WHERE key = 'rules'",
                (rs, n) -> rs.getString("value"));
        if (rows.isEmpty() || rows.getFirst() == null) {
            return DevflowTypes.Settings.defaults();
        }
        try {
            return json.readValue(rows.getFirst(), DevflowTypes.Settings.class);
        } catch (Exception e) {
            return DevflowTypes.Settings.defaults();
        }
    }

    @Override
    public void saveSettings(DevflowTypes.Settings settings, String actor) {
        jdbc.update("""
                INSERT INTO devflow_setting (key, value_json, updated_by)
                VALUES ('rules', ?::jsonb, ?)
                ON CONFLICT (key) DO UPDATE
                SET value_json = EXCLUDED.value_json, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, write(settings), actor);
    }

    @Override
    public void stage(String jobId, String stage, String actor, int attempt, String status, String traceId, Double cost, String summary) {
        jdbc.update("""
                INSERT INTO devflow_stage_run (job_id, stage, actor, attempt, status, trace_id, cost_cny, summary, ended_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, jobId, stage, actor, attempt, status, traceId, cost, summary, Timestamp.from(Instant.now()));
    }

    @Override
    public void artifact(String jobId, DevflowTypes.Artifact artifact) {
        jdbc.update("""
                INSERT INTO devflow_artifact (job_id, kind, ref, sha256, origin) VALUES (?, ?, ?, ?, ?)
                """, jobId, artifact.kind(), artifact.ref(), artifact.sha256(), artifact.origin());
    }

    private DevflowTypes.Job job(ResultSet rs, int row) throws SQLException {
        return new DevflowTypes.Job(rs.getString("job_id"), rs.getString("title"), rs.getString("layer"),
                rs.getString("kind"), rs.getString("mode"), rs.getString("status"), rs.getString("stage"),
                rs.getString("target_agent"), rs.getString("producer_agent"), rs.getString("template"),
                money(rs, "spent_cny"), money(rs, "budget_cny"), rs.getInt("fix_rounds"), rs.getInt("max_fix_rounds"),
                rs.getString("batch_id"), rs.getBoolean("need_review"), rs.getString("human_dev_user"),
                (Integer) rs.getObject("watch_day"), rs.getString("requester"), rs.getString("owner_org"),
                rs.getString("goal"), read(rs.getString("tools_json"), TOOLS), read(rs.getString("knowledge_json"), NAMES),
                read(rs.getString("seed_json"), DevflowTypes.Seed.class), read(rs.getString("events_json"), EVENTS));
    }

    private String selectJob() {
        return """
                SELECT job_id, title, layer, kind, mode, status, stage, target_agent, producer_agent, template,
                       spent_cny, budget_cny, fix_rounds, max_fix_rounds, batch_id, need_review, human_dev_user,
                       watch_day, requester, owner_org, goal, tools_json::text AS tools_json,
                       knowledge_json::text AS knowledge_json, seed_json::text AS seed_json, events_json::text AS events_json
                FROM devflow_job
                """;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String raw, Class<T> type) throws SQLException {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type) throws SQLException {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    private static double money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? 0 : value.doubleValue();
    }

    private static String day(Timestamp value) {
        if (value == null) {
            return "";
        }
        return DAY.format(value.toInstant().atZone(SHANGHAI));
    }
}
