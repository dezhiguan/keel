package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GateBoardTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void prodGateWinsAndAMissingScoreStaysEmpty() throws Exception {
        var state = json.readTree("""
                {"gate":{"visibleScore":0.74,"hiddenScore":0.71,"visibleByTag":{"safety":0.74}},
                 "prodGate":{"visibleScore":0.86,"hiddenScore":0.84},"humanSeeds":[{"input":"secret"}]}
                """);
        var row = GateBoard.jobRow("release-notes", "DF-0019", state, 0.5);
        assertThat(row).containsOnlyKeys("agent", "jobId", "visible", "holdout", "passed");
        assertThat(row).containsEntry("visible", 0.86).containsEntry("holdout", 0.84).containsEntry("passed", true);
        assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains("secret") || String.valueOf(value).contains("safety"));
    }

    @Test void holdoutResultFillsAMissingHiddenScore() throws Exception {
        var state = json.readTree("{\"gate\":{\"visibleScore\":0.74}}");
        var row = GateBoard.jobRow("release-notes", "DF-0019", state, 0.71);
        assertThat(row).containsEntry("visible", 0.74).containsEntry("holdout", 0.71).containsEntry("passed", false);
    }

    @Test void aJobWithoutScoresIsNotPassed() {
        var row = GateBoard.jobRow("weekly-report", "DF-0020", null, null);
        assertThat(row).containsEntry("visible", null).containsEntry("holdout", null).containsEntry("passed", null);
    }

    @Test void manualAgentsKeepHoldoutEmptyAndStayInsideTheEnvironment() {
        var jobs = List.of(GateBoard.jobRow("release-notes", "DF-0019", null, null));
        var rows = GateBoard.assemble(jobs, Set.of("release-notes", "askdb"), Set.of("dev-agent"),
                Map.of("askdb", 0.88, "dev-agent", 0.9, "careermate", 0.91, "release-notes", 0.7));
        assertThat(rows).extracting(row -> row.get("agent")).containsExactly("release-notes", "askdb");
        assertThat(rows.get(1)).containsEntry("jobId", null).containsEntry("visible", 0.88)
                .containsEntry("holdout", null).containsEntry("passed", true);
    }
}
