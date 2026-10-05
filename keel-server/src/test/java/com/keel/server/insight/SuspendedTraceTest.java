package com.keel.server.insight;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuspendedTraceTest {
    @Test void aSuspendedRunBecomesATraceTheConsoleCanOpen() {
        var created = Instant.parse("2026-10-05T08:40:00Z");
        var detail = SuspendedTrace.fromRun("tr-1", "deploy-check-run", "echo", "请回复", "amy",
                "SUSPENDED", "input_required", created, created.plusSeconds(372));
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) detail.get("summary");
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) detail.get("nodes");
        assertThat(summary.get("question")).isEqualTo("请回复");
        assertThat(summary.get("userId")).isEqualTo("amy");
        assertThat(summary.get("humanWaitMs")).isEqualTo(372_000L);
        assertThat(nodes.getFirst().get("name")).isEqualTo("等用户回答");
        assertThat(nodes.getFirst().get("humanWaitLabel")).isEqualTo("6m12s");
        assertThat(nodes.getFirst().get("outputSummary")).isEqualTo("挂起中");
        assertThat(detail.get("edges")).asList().isEmpty();
    }

    @Test void aFinishedRunDoesNotKeepCountingTheWait() {
        var detail = SuspendedTrace.fromRun("tr-2", "r", null, "", null, "DONE", "handoff",
                Instant.parse("2026-10-05T08:00:00Z"), Instant.parse("2026-10-05T09:00:00Z"));
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) detail.get("summary");
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) detail.get("nodes");
        assertThat(summary.get("rootAgent")).isEqualTo("keel");
        assertThat(summary.get("humanWaitMs")).isEqualTo(0L);
        assertThat(nodes.getFirst().get("name")).isEqualTo("转人工");
        assertThat(nodes.getFirst().get("outputSummary")).isEqualTo("已恢复");
        assertThat(nodes.getFirst().get("humanWaitLabel")).isNull();
    }
}
