package com.keel.server.registry.service;

import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.AgentVersion;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class VersionLifecycleTest {
    @Test void registeredPrefersNewestFormalSnapshotOverLaterDraft() {
        var draft = version("v0", "staging", "draft", 5);
        var formal = version("v3", "staging", "formal", 3);
        formal.setGateRunId("ev_v3");
        assertThat(VersionLifecycle.registered(List.of(draft, formal)).getVersion()).isEqualTo("v3");
        assertThat(VersionLifecycle.registered(List.of(draft)).getVersion()).isEqualTo("v0");
        assertThat(VersionLifecycle.registered(List.of())).isNull();
    }

    @Test void archivesOnlyAnOlderCandidateMatchingItsOwnSourceEnvironment() {
        var draft = version("v0", "dev", "same", 1);
        var snapshot = version("v3", "dev", "same", 2);
        assertThat(rows(draft, snapshot).getFirst().state()).isEqualTo("ARCHIVED");
        draft.setManifestHash("new-work");
        assertThat(rows(draft, snapshot).getFirst().state()).isEqualTo("DRAFT");
        draft.setManifestHash("same"); draft.setReleasedAt(snapshot.getReleasedAt().plusSeconds(1));
        assertThat(rows(draft, snapshot).getFirst().state()).isEqualTo("DRAFT");
        draft.setReleasedAt(snapshot.getReleasedAt().minusSeconds(1)); snapshot.setEnv("test");
        assertThat(rows(draft, snapshot).getFirst().state()).isEqualTo("DRAFT");
    }

    @Test void sameVersionNumberAcrossSourceEnvironmentsDoesNotMislabelPublication() {
        var dev = version("v1", "dev", "a", 1);
        dev.setGateRunId("ev_dev"); dev.setImage("image:dev");
        var test = version("v1", "test", "b", 2);
        test.setGateRunId("ev_test"); test.setImage("image:test");
        var versions = List.of(dev, test);
        var rows = VersionLifecycle.attach(versions, versions.stream().map(AgentDetail.VersionRow::of).toList(),
                List.of(new VersionLifecycle.Publication("prod", "v1", "ev_dev", "image:dev")));
        assertThat(rows.getFirst().publishedEnvs()).containsExactly("prod");
        assertThat(rows.getLast().publishedEnvs()).isEmpty();
    }

    private List<AgentDetail.VersionRow> rows(AgentVersion... versions) {
        var list = List.of(versions);
        return VersionLifecycle.attach(list, list.stream().map(AgentDetail.VersionRow::of).toList(), List.of());
    }
    private AgentVersion version(String number, String env, String hash, int day) {
        var version = new AgentVersion(); version.setVersion(number); version.setEnv(env);
        version.setManifestHash(hash); version.setReleasedAt(OffsetDateTime.parse("2026-10-0"+day+"T00:00:00Z"));
        return version;
    }
}
