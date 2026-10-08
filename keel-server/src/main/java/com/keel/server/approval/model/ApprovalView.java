package com.keel.server.approval.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/** Console Approval object. Field names match contracts/console-api.openapi.yaml. */
public record ApprovalView(
        String id,
        String subjectType,
        String subjectRef,
        String runId,
        String policyName,
        String agent,
        String tool,
        String risk,
        String traceId,
        String actorUser,
        String summary,
        String payloadDigest,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        String decidedBy,
        OffsetDateTime decidedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String devflowJobId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String devflowGate) {}
