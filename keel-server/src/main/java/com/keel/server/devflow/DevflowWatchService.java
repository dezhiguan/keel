package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.server.insight.AgentUsageService;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.Map;

/** Reconciles trusted CI releases and observes each produced employee for seven days. */
@Service
public class DevflowWatchService {
    private static final Logger log = LoggerFactory.getLogger(DevflowWatchService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final double MIN_QUALITY = HoldoutRules.MIN_SCORE;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final DevflowService jobs;
    private final AgentUsageService usage;
    private final KubernetesClient cluster;

    public DevflowWatchService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
                               DevflowService jobs, AgentUsageService usage, KubernetesClient cluster) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.jobs = jobs;
        this.usage = usage;
        this.cluster = cluster;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void reconcile() {
        var releasing = jdbc.query("""
                SELECT job_id FROM devflow_execution WHERE state_json->>'phase'='RELEASING'
                """, (rs, row) -> rs.getString(1));
        for (var jobId : releasing) {
            try { startIfReleased(jobId); }
            catch (RuntimeException error) {
                log.warn("watch release reconciliation failed trace_id= agent=meta-agent job={} error={}",
                        jobId, error.getClass().getSimpleName());
            }
        }
        var watching = jdbc.query("""
                SELECT job_id FROM devflow_execution WHERE state_json->>'phase'='WATCHING'
                """, (rs, row) -> rs.getString(1));
        if (watching.isEmpty()) return;
        AgentUsageService.Snapshot metrics;
        try { metrics = usage.snapshotOf("prod"); }
        catch (RuntimeException error) {
            log.warn("watch metrics unavailable trace_id= agent=meta-agent error={}", error.getClass().getSimpleName());
            return;
        }
        for (var jobId : watching) {
            try { observe(jobId, metrics, Instant.now()); }
            catch (RuntimeException error) {
                log.warn("watch observation failed trace_id= agent=meta-agent job={} error={}",
                        jobId, error.getClass().getSimpleName());
            }
        }
    }

    private void startIfReleased(String jobId) {
        tx.executeWithoutResult(status -> {
            var state = lock(jobId);
            if (!"RELEASING".equals(state.path("phase").asText())) return;
            var gate = state.path("prodGate");
            if (!gate.path("passed").asBoolean(false)
                    || !state.path("mergeSha").asText().equals(gate.path("sha").asText())) return;
            var agent = state.path("requirement").path("target_agent").asText();
            var releases = jdbc.query("""
                    SELECT created_at, image FROM release_record
                    WHERE agent_name=? AND env='prod' AND kind='release' AND gate_passed
                      AND ci_run_url=? ORDER BY created_at DESC LIMIT 1
                    """, (rs, row) -> new Release(rs.getTimestamp(1), rs.getString(2)),
                    agent, gate.path("runId").asText());
            if (releases.isEmpty()) return;
            var release = releases.getFirst();
            if (release.image() == null || !release.image().matches("[^\\s]+@sha256:[0-9a-f]{64}")
                    || !deploymentReady(agent, state.path("mergeSha").asText(), release.image())) return;
            state.put("phase", "WATCHING");
            state.put("watchStartedAt", release.at().toInstant().toString());
            state.put("watchImage", release.image());
            state.set("watchObservations", json.createArrayNode());
            save(jobId, state);
            jobs.progress(jobId, "RUN", "WATCH", "可信 CI 已发布 prod，开始七天质量、调用量与成本观察");
        });
    }

    private void observe(String jobId, AgentUsageService.Snapshot metrics, Instant now) {
        tx.executeWithoutResult(status -> {
            var state = lock(jobId);
            if (!"WATCHING".equals(state.path("phase").asText())) return;
            var agent = state.path("requirement").path("target_agent").asText();
            if (!deploymentReady(agent, state.path("mergeSha").asText(), state.path("watchImage").asText())) return;
            var quality = metrics.scores().get(agent);
            if (!metrics.callsKnown() || !metrics.costKnown() || quality == null
                    || !Double.isFinite(quality) || quality < 0 || quality > 1) return;
            var started = Instant.parse(state.path("watchStartedAt").asText());
            if (Duration.between(started, now).compareTo(Duration.ofDays(1)) < 0) return;
            var day = now.atZone(SHANGHAI).toLocalDate().toString();
            var observations = (ArrayNode) state.path("watchObservations");
            if (!observations.isEmpty() && day.equals(observations.get(observations.size() - 1).path("day").asText())) return;
            var calls = metrics.calls().getOrDefault(agent, 0L);
            var cost = metrics.cost().getOrDefault(agent, 0.0);
            var budget = state.path("requirement").path("dailyBudgetCny").asDouble(0);
            if (!Double.isFinite(cost) || cost < 0 || budget <= 0) return;
            var healthy = quality >= MIN_QUALITY && cost <= budget && calls > 0;
            var sample = observations.addObject();
            sample.put("day", day).put("quality", quality).put("costCny", cost)
                    .put("budgetCny", budget).put("calls", calls).put("healthy", healthy);
            sample.put("observedAt", now.toString());
            if (Duration.between(started, now).compareTo(Duration.ofDays(7)) >= 0 && sevenHealthyDays(observations)) {
                state.put("phase", "DONE");
                save(jobId, state);
                jobs.progress(jobId, "DONE", "WATCH", "连续七个观察日质量、成本和调用量合格，研发员工完成上岗观察");
            } else {
                save(jobId, state);
                if (!healthy) jobs.progress(jobId, "WAIT", "WATCH", "观察发现质量、成本或调用量异常，等待恢复后继续计满七天");
            }
        });
    }

    static boolean sevenHealthyDays(ArrayNode observations) {
        if (observations == null || observations.size() < 7) return false;
        LocalDate expected = null;
        for (int index = observations.size() - 1; index >= observations.size() - 7; index--) {
            var row = observations.get(index);
            if (!row.path("healthy").asBoolean(false)) return false;
            LocalDate day;
            try { day = LocalDate.parse(row.path("day").asText()); }
            catch (RuntimeException error) { return false; }
            if (expected != null && !expected.equals(day)) return false;
            expected = day.minusDays(1);
        }
        return true;
    }

    private ObjectNode lock(String jobId) {
        var states = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=? FOR UPDATE",
                (rs, row) -> read(rs.getString(1)), jobId);
        if (states.isEmpty()) throw new IllegalStateException("missing execution");
        return states.getFirst();
    }

    private ObjectNode read(String body) {
        try { return (ObjectNode) json.readTree(body); }
        catch (Exception error) { throw new IllegalStateException("invalid execution", error); }
    }

    private void save(String jobId, ObjectNode state) {
        jdbc.update("UPDATE devflow_execution SET state_json=?::jsonb, updated_at=now() WHERE job_id=?",
                state.toString(), jobId);
    }

    private boolean deploymentReady(String agent, String sha, String image) {
        if (agent.isBlank() || !sha.matches("[0-9a-f]{40}") || image.isBlank()) return false;
        Deployment deployment = cluster.apps().deployments().inNamespace("agents")
                .withName(agent + "-prod").get();
        return deploymentReady(deployment, agent, sha, image);
    }

    static boolean deploymentReady(Deployment deployment, String agent, String sha, String image) {
        if (deployment == null || deployment.getMetadata() == null || deployment.getSpec() == null
                || deployment.getStatus() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null) return false;
        var metadata = deployment.getMetadata();
        var template = deployment.getSpec().getTemplate();
        if (!labelsMatch(metadata.getLabels(), agent, sha) || template.getMetadata() == null
                || !labelsMatch(template.getMetadata().getLabels(), agent, sha)) return false;
        var desired = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
        var status = deployment.getStatus();
        if (desired < 1 || metadata.getGeneration() == null || status.getObservedGeneration() == null
                || status.getObservedGeneration() < metadata.getGeneration()
                || status.getReadyReplicas() == null || status.getReadyReplicas() < desired
                || status.getUpdatedReplicas() == null || status.getUpdatedReplicas() < desired
                || status.getAvailableReplicas() == null || status.getAvailableReplicas() < desired) return false;
        return template.getSpec().getContainers() != null
                && template.getSpec().getContainers().stream().anyMatch(container -> image.equals(container.getImage()));
    }

    private static boolean labelsMatch(Map<String, String> labels, String agent, String sha) {
        return labels != null && agent.equals(labels.get("keel.io/agent"))
                && "prod".equals(labels.get("keel.io/env")) && sha.equals(labels.get("keel.io/commit"));
    }

    private record Release(Timestamp at, String image) {}
}
