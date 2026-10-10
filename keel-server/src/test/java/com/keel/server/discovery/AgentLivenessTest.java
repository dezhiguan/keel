package com.keel.server.discovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLivenessTest {
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");

    @Test void readyInstancePromotesRegisteredAndOfflineToOnline() {
        var serving = List.of(new AgentLiveness.Sighting(true));
        assertThat(AgentLiveness.next("REGISTERED", serving)).isEqualTo("ONLINE");
        assertThat(AgentLiveness.next("OFFLINE", serving)).isEqualTo("ONLINE");
        assertThat(AgentLiveness.next("DEGRADED", serving)).isEqualTo("ONLINE");
    }

    @Test void noServingInstanceIsOffline() {
        assertThat(AgentLiveness.next("REGISTERED", List.of())).isEqualTo("OFFLINE");
        assertThat(AgentLiveness.next("ONLINE", List.of(new AgentLiveness.Sighting(false)))).isEqualTo("OFFLINE");
        assertThat(AgentLiveness.next("DEGRADED", List.of())).isEqualTo("OFFLINE");
    }

    @Test void mixedReadinessIsDegraded() {
        var mixed = List.of(new AgentLiveness.Sighting(true), new AgentLiveness.Sighting(false));
        assertThat(AgentLiveness.next("ONLINE", mixed)).isEqualTo("DEGRADED");
        assertThat(AgentLiveness.next("OFFLINE", mixed)).isEqualTo("DEGRADED");
    }

    @Test void draftAndRetiredAreNotTouched() {
        assertThat(AgentLiveness.next("DRAFT", List.of(new AgentLiveness.Sighting(true)))).isEqualTo("DRAFT");
        assertThat(AgentLiveness.next("RETIRED", List.of())).isEqualTo("RETIRED");
        assertThat(AgentLiveness.next(null, List.of())).isNull();
    }

    @Test void kubernetesReadyDoesNotExpireButHeartbeatDoes() {
        var stale = NOW.minusSeconds(46);
        var fresh = NOW.minusSeconds(44);
        assertThat(AgentLiveness.serving("k8s", true, stale, NOW, null)).isTrue();
        assertThat(AgentLiveness.serving("k8s", true, stale, NOW, true)).isTrue();
        assertThat(AgentLiveness.serving("k8s", true, fresh, NOW, false)).isFalse();
        assertThat(AgentLiveness.serving("k8s", false, fresh, NOW, true)).isFalse();
        assertThat(AgentLiveness.serving("heartbeat", true, fresh, NOW, null)).isTrue();
        assertThat(AgentLiveness.serving("heartbeat", true, stale, NOW, null)).isFalse();
        assertThat(AgentLiveness.serving("probe", true, stale, NOW, null)).isFalse();
        assertThat(AgentLiveness.serving("probe", false, fresh, NOW, null)).isFalse();
    }
}
