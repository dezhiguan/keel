package com.keel.server.approval.model;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        OffsetDateTime deadline,
        @JsonInclude(JsonInclude.Include.NON_NULL) String devflowJobId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String devflowGate) {}
