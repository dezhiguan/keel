package com.keel.server.registry.service;

import com.keel.server.registry.model.entity.AgentVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VersionEvidenceTest {
    @Test void versionRunWinsAndAReleaseFillsTheRowThatHasNoRun() {
        var current = version("v2", "prod", "ev_current");
        var released = version("v1", "prod", null);
        var plain = version("v0", "dev", null);
        var rows = VersionEvidence.rows(List.of(current, released, plain), Map.of(
                "ev_current", new VersionEvidence.EvalResult(0.91, true),
                "ev_old", new VersionEvidence.EvalResult(0.80, false)
        ), Map.of(
                "v2\nprod", new VersionEvidence.ReleaseResult(true, 0.10, "ev_other"),
                "v1\nprod", new VersionEvidence.ReleaseResult(true, 0.50, "ev_old")
        ));
        assertThat(rows).extracting(row -> row.version() + ":" + row.scoreTotal() + ":" + row.gatePassed())
                .containsExactly("v2:0.91:true", "v1:0.8:false", "v0:null:null");
    }

    @Test void aMissingVersionRunFallsBackToTheReleaseRun() {
        var version = version("v1", "dev", "ev_gone");
        var rows = VersionEvidence.rows(List.of(version), Map.of(
                "ev_fallback", new VersionEvidence.EvalResult(0.70, true)
        ), Map.of("v1\ndev", new VersionEvidence.ReleaseResult(false, null, "ev_fallback")));
        assertThat(rows.getFirst().scoreTotal()).isEqualTo(0.70);
        assertThat(rows.getFirst().gatePassed()).isTrue();
    }

    @Test void aReleaseWithoutAnEvaluationStillShowsItsStoredGateAndScore() {
        var version = version("v0", "prod", null);
        var rows = VersionEvidence.rows(List.of(version), Map.of(),
                Map.of("v0\nprod", new VersionEvidence.ReleaseResult(true, 0.66, "legacy")));
        assertThat(rows.getFirst().scoreTotal()).isEqualTo(0.66);
        assertThat(rows.getFirst().gatePassed()).isTrue();
    }

    private static AgentVersion version(String version, String env, String runId) {
        var row = new AgentVersion();
        row.setVersion(version);
        row.setEnv(env);
        row.setGateRunId(runId);
        row.setReleasedBy("amy");
        return row;
    }
}
