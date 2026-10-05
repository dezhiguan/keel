package com.keel.server.approval.model;

public record OpenRun(
        String runId,
        String agent,
        String env,
        String traceId,
        String reason,
        String prompt,
        String actorUser,
        String deadline,
        String checkpointRef) {}
