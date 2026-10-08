package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    @Test
    void onlyTheNamedAgentsCanStartARunInTheKeelAgentsOrg() {
        var harness = harness();
        assertThatThrownBy(() -> harness.service.start("DF-0001", "refund-explainer", "refs/heads/feature", "code-review", NOW))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.DEVFLOW_GRANT_MISSING);
        assertThatThrownBy(() -> harness.service.start("DF-0099", "refund-explainer", "refs/heads/feature", "meta-agent", NOW))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_NOT_FOUND);
        harness.job("DF-0001");
        assertThatThrownBy(() -> harness.service.start("DF-0001", "Bad", "refs/heads/feature", "dev-agent", NOW))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
        assertThatThrownBy(() -> harness.service.start("DF-0001", "refund-explainer", "main", "eval-agent", NOW))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);

        var run = harness.service.start("DF-0001", "refund-explainer", "refs/heads/feature", "meta-agent", NOW);
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.runId()).isEqualTo("SB-0001");
        assertThat(run.truncated()).isFalse();
        assertThat(harness.cluster.submitted).containsExactly("SB-0001");
        assertThat(harness.service.start("DF-0001", "refund-explainer", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "eval-agent", NOW).status()).isEqualTo("RUNNING");
    }

    @Test
    void theFifthRunWaitsUntilASlotFreesAndALongReportIsCut() {
        var harness = harness();
        harness.job("DF-0001");
        for (int i = 0; i < 4; i++) {
            assertThat(harness.service.start("DF-0001", "refund-explainer", "refs/heads/feature", "meta-agent", NOW).status())
                    .isEqualTo("RUNNING");
        }
        var queued = harness.service.start("DF-0001", "refund-explainer", "refs/heads/feature", "dev-agent", NOW);
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(harness.cluster.submitted).hasSize(4);

        harness.cluster.observations.put("SB-0001", new SandboxCluster.Observation(
                SandboxCluster.Phase.SUCCEEDED, "x".repeat(70_000)));
        var done = harness.service.get("SB-0001", NOW);
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.truncated()).isTrue();
        assertThat(done.report().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(SandboxRules.REPORT_BYTES);
        assertThat(harness.cluster.deleted).contains("SB-0001");
        assertThat(harness.service.get("SB-0005", NOW).status()).isEqualTo("RUNNING");
        assertThat(harness.cluster.submitted).contains("SB-0005");
    }

    @Test
    void aRunPastTenMinutesIsTimeout() {
        var harness = harness();
        harness.job("DF-0001");
        var run = harness.service.start("DF-0001", "refund-explainer", "refs/heads/feature", "meta-agent", NOW);
        assertThatThrownBy(() -> harness.service.get(run.runId(), NOW.plus(SandboxService.LIMIT)))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.DEVFLOW_SANDBOX_TIMEOUT);
        assertThat(harness.store.find(run.runId()).status()).isEqualTo("TIMEOUT");
        assertThat(harness.cluster.deleted).contains(run.runId());
        assertThatThrownBy(() -> harness.service.get("SB-4040", NOW))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_NOT_FOUND);
    }

    private static Harness harness() {
        var ledger = new MemoryDevflowLedger();
        var store = new MemorySandboxStore();
        var cluster = new MemoryCluster();
        return new Harness(ledger, store, cluster, new SandboxService(ledger, store, cluster));
    }

    private record Harness(MemoryDevflowLedger ledger, MemorySandboxStore store, MemoryCluster cluster, SandboxService service) {
        void job(String jobId) {
            ledger.insert(new DevflowTypes.Job(jobId, "标题", "DEV", "CREATE", "COLLAB", "RUN", "BUILD",
                    "weekly-report", "meta-agent", "graph-agent", 0, 120, 0, 3, null, false, null, null,
                    "官德志", "研发效能组", "目标", List.of(), List.of(), new DevflowTypes.Seed(0, 0, 0), List.of()));
        }
    }

    private static final class MemoryCluster implements SandboxCluster {
        private final List<String> submitted = new java.util.ArrayList<>();
        private final List<String> deleted = new java.util.ArrayList<>();
        private final Map<String, Observation> observations = new HashMap<>();

        @Override
        public void submit(SandboxRecord run) {
            submitted.add(run.runId());
        }

        @Override
        public Observation observe(String runId) {
            return observations.getOrDefault(runId, new Observation(Phase.RUNNING, ""));
        }

        @Override
        public void delete(String runId) {
            deleted.add(runId);
        }
    }
}
