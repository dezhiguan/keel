package com.keel.server.devflow;

import java.time.Instant;

/** Stored sandbox run. The JSON view is {@link DevflowTypes.SandboxRun}. */
public record SandboxRecord(String runId, String jobId, String repo, String ref, String status, String report,
                            boolean truncated, String actor, Instant createdAt, Instant deadlineAt) {
    static SandboxRecord create(String runId, String jobId, String repo, String ref, String status, String actor,
                                Instant createdAt, Instant deadlineAt) {
        return new SandboxRecord(runId, jobId, repo, ref, status, "", false, actor, createdAt, deadlineAt);
    }

    SandboxRecord finish(String status, String report, boolean truncated) {
        return new SandboxRecord(runId, jobId, repo, ref, status, report == null ? "" : report, truncated, actor,
                createdAt, deadlineAt);
    }
}
