package com.keel.server.approval.model;

import java.time.OffsetDateTime;

/** Console SuspendedRun object. reason is input_required or handoff. */
public record SuspendedRunView(
        String runId,
        String agent,
        String env,
        String traceId,
        String reason,
        String prompt,
        String actorUser,
        OffsetDateTime createdAt,
        OffsetDateTime deadline) {}
