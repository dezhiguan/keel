package com.keel.server.discovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcileRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");

    @Test void fiveFindingsAndAHealthyAgent() {
        assertThat(kind("ONLINE", true, false, false, NOW, "v1", "v1", true, false)).contains(ReconcileRules.Kind.OFFLINE);
        assertThat(kind("ONLINE", true, true, true, NOW.minusSeconds(46), "v1", "v1", true, false)).contains(ReconcileRules.Kind.OFFLINE);
        assertThat(kind(null, false, true, true, NOW, null, null, null, null)).contains(ReconcileRules.Kind.UNREGISTERED);
        assertThat(kind("ONLINE", true, true, true, NOW, "v1", "v1", false, false)).contains(ReconcileRules.Kind.ZOMBIE);
        assertThat(kind("RETIRED", true, true, true, NOW, "v1", "v1", false, false)).contains(ReconcileRules.Kind.RETIRE_INCOMPLETE);
        assertThat(kind("ONLINE", true, true, true, NOW, "v0", "v1", true, false)).contains(ReconcileRules.Kind.VERSION_MISMATCH);
        assertThat(kind("ONLINE", true, true, true, NOW, "v1", "v1", true, false)).isEmpty();
    }

    @Test void bothTrafficSourcesDownDoesNotCreateAZombie() {
        assertThat(kind("ONLINE", true, true, true, NOW, "v1", "v1", null, null)).isEmpty();
    }

    @Test void eitherTrafficSourceCounts() {
        assertThat(kind("ONLINE", true, true, true, NOW, "v1", "v1", false, true)).isEmpty();
        assertThat(kind("ONLINE", true, true, true, NOW, "v1", "v1", true, false)).isEmpty();
    }

    private static java.util.Optional<ReconcileRules.Kind> kind(String status, boolean registered, boolean running, boolean ready,
                                                                Instant seen, String instanceVersion, String registeredVersion,
                                                                Boolean langfuse, Boolean gateway) {
        return ReconcileRules.classify(new ReconcileRules.Input(
                status, registered, running, ready, seen, NOW, instanceVersion, registeredVersion, langfuse, gateway));
    }
}
