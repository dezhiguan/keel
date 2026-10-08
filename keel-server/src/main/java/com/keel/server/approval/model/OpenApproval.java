package com.keel.server.approval.model;

public record OpenApproval(
        String subjectType,
        String subjectRef,
        String summary,
        String actorUser,
        String agent,
        String risk,
        String runId,
        String traceId,
        String payloadDigest,
        String policyName,
        String env,
        String consentId) {}
