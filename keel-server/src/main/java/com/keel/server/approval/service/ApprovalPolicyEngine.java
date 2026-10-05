package com.keel.server.approval.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Pure approval and resume transitions. Time and persistence stay outside. */
public final class ApprovalPolicyEngine {
    public static final Duration RESUME_WINDOW = Duration.ofMinutes(10);
    private static final Set<String> SUBJECTS = Set.of(
            "tool.call", "tool.config", "agent.config", "agent.retire", "data.export");
    private static final Set<String> HUMAN_REASONS = Set.of("input_required", "handoff");
    private static final Set<String> ENVS = Set.of("dev", "test", "staging", "prod");

    public enum Verdict { APPROVE, REJECT, EXPIRE, CLOSED, INVALID }

    public enum Resume { ALLOW, DENIED, NOT_RESUMABLE, EXPIRED, NOT_FOUND }

    private ApprovalPolicyEngine() {}

    public static Verdict decide(String status, Instant expiresAt, String decision, Instant now) {
        if (!"PENDING".equals(status)) {
            return Verdict.CLOSED;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return Verdict.EXPIRE;
        }
        if ("APPROVE".equals(decision)) {
            return Verdict.APPROVE;
        }
        if ("REJECT".equals(decision)) {
            return Verdict.REJECT;
        }
        return Verdict.INVALID;
    }

    /** True when a previous approval still suppresses a new pending request. */
    public static boolean inCooldown(Instant decidedAt, int cooldownHours, Instant now) {
        if (cooldownHours <= 0 || decidedAt == null) {
            return false;
        }
        return now.isBefore(decidedAt.plus(Duration.ofHours(cooldownHours)));
    }

    public static Resume resume(String status, String reason, Instant deadline, Instant suspendedAt, Instant now) {
        if (status == null) {
            return Resume.NOT_FOUND;
        }
        if (!"SUSPENDED".equals(status) || !HUMAN_REASONS.contains(reason == null ? "" : reason)) {
            return Resume.NOT_RESUMABLE;
        }
        if (deadline != null && !now.isBefore(deadline)) {
            return Resume.EXPIRED;
        }
        if (suspendedAt == null || Duration.between(suspendedAt, now).compareTo(RESUME_WINDOW) > 0) {
            return Resume.DENIED;
        }
        return Resume.ALLOW;
    }

    public static boolean resumeWindowOpen(Instant suspendedAt, Instant now) {
        return suspendedAt != null && Duration.between(suspendedAt, now).compareTo(RESUME_WINDOW) <= 0;
    }

    public static long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw notFound();
        }
        var text = raw.startsWith("ap_") ? raw.substring(3) : raw;
        try {
            var id = Long.parseLong(text);
            if (id <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return id;
        } catch (NumberFormatException e) {
            throw notFound();
        }
    }

    public static String publicId(long id) {
        return "ap_" + id;
    }

    public static void checkSubject(String subjectType) {
        if (!SUBJECTS.contains(subjectType)) {
            throw invalid();
        }
    }

    public static String risk(String raw) {
        if (raw == null || raw.isBlank()) {
            return "HIGH";
        }
        var normalized = raw.trim().toUpperCase();
        if (!Set.of("LOW", "MID", "HIGH").contains(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    public static String env(String raw) {
        if (raw == null || raw.isBlank()) {
            return "prod";
        }
        if (!ENVS.contains(raw)) {
            throw invalid();
        }
        return raw;
    }

    /** Query scope. {@code all}, blank, and null mean no environment filter. */
    public static String scope(String raw) {
        if (raw == null || raw.isBlank() || "all".equals(raw)) {
            return "";
        }
        if (!ENVS.contains(raw)) {
            throw invalid();
        }
        return raw;
    }

    public static boolean runId(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static KeelException notFound() {
        return new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
    }

    private static KeelException invalid() {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
    }
}
