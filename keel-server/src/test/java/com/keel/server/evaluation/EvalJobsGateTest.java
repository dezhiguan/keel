package com.keel.server.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalJobsGateTest {
    @Test void finishedRunFillsTheGateLine() {
        var passed = EvalJobs.gateItem(Map.of(
                "scoreTotal", 0.9,
                "passed", true,
                "gate", Map.of("minScore", 0.85)));
        assertThat(passed.name()).isEqualTo("评测门禁");
        assertThat(passed.passed()).isTrue();
        assertThat(passed.detail()).isEqualTo("0.90 ≥ 0.85");

        var failed = EvalJobs.gateItem(Map.of("scoreTotal", 0.5, "passed", false));
        assertThat(failed.passed()).isFalse();
        assertThat(failed.detail()).isEqualTo("0.50 < 0.85");

        // Score cleared the threshold even if the raw run flag stayed false.
        var scoreCleared = EvalJobs.gateItem(Map.of("scoreTotal", 0.95, "passed", false,
                "gate", Map.of("minScore", 0.85)));
        assertThat(scoreCleared.passed()).isTrue();
        assertThat(scoreCleared.detail()).isEqualTo("0.95 ≥ 0.85");
    }

    @Test void missingRunStaysUnrun() {
        var item = EvalJobs.gateItem(null);
        assertThat(item.passed()).isNull();
        assertThat(item.detail()).isEqualTo("未运行");
        assertThat(EvalJobs.gateItem(Map.of("passed", false)).detail()).isEqualTo("未运行");
    }
}
