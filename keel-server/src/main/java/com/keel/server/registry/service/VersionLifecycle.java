package com.keel.server.registry.service;

import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.AgentVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Separate editable candidates from snapshots, and source environments from release targets. */
final class VersionLifecycle {
    private VersionLifecycle() {}

    static List<AgentDetail.VersionRow> attach(List<AgentVersion> versions, List<AgentDetail.VersionRow> rows,
                                               List<Publication> publications) {
        var out = new ArrayList<AgentDetail.VersionRow>();
        for (int i = 0; i < versions.size(); i++) {
            var version = versions.get(i);
            boolean draft = "v0".equals(version.getVersion()) && blank(version.getGateRunId());
            boolean archived = draft && versions.stream().anyMatch(snapshot ->
                    !"v0".equals(snapshot.getVersion()) && Objects.equals(snapshot.getEnv(), version.getEnv())
                    && !blank(version.getManifestHash()) && version.getManifestHash().equals(snapshot.getManifestHash())
                    && snapshot.getReleasedAt() != null && version.getReleasedAt() != null
                    && snapshot.getReleasedAt().isAfter(version.getReleasedAt()));
            var envs = draft ? List.<String>of() : publications.stream().filter(p -> p.matches(version))
                    .map(Publication::env).distinct().sorted().toList();
            out.add(rows.get(i).withLifecycle(draft ? archived ? "ARCHIVED" : "DRAFT" : "VERSION", envs));
        }
        return List.copyOf(out);
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }

    record Publication(String env, String version, String runId, String image) {
        boolean matches(AgentVersion snapshot) {
            if (!Objects.equals(version, snapshot.getVersion())) return false;
            if (!blank(snapshot.getGateRunId())) {
                return snapshot.getGateRunId().equals(runId) && !blank(image) && image.equals(snapshot.getImage());
            }
            return Objects.equals(env, snapshot.getEnv())
                    && (blank(image) || Objects.equals(image, snapshot.getImage()));
        }
    }
}
