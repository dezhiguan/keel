package com.keel.server.approval.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalPolicyEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    @Test void decideOnlyMovesALivePendingRequest() {
        assertThat(ApprovalPolicyEngine.decide("APPROVED", NOW, "APPROVE", NOW)).isEqualTo(ApprovalPolicyEngine.Verdict.CLOSED);
        assertThat(ApprovalPolicyEngine.decide("PENDING", NOW, "APPROVE", NOW)).isEqualTo(ApprovalPolicyEngine.Verdict.EXPIRE);
        assertThat(ApprovalPolicyEngine.decide("PENDING", NOW.minusSeconds(1), "REJECT", NOW)).isEqualTo(ApprovalPolicyEngine.Verdict.EXPIRE);
        assertThat(ApprovalPolicyEngine.decide("PENDING", NOW.plusSeconds(1), "APPROVE", NOW)).isEqualTo(ApprovalPolicyEngine.Verdict.APPROVE);
        assertThat(ApprovalPolicyEngine.decide("PENDING", null, "REJECT", NOW)).isEqualTo(ApprovalPolicyEngine.Verdict.REJECT);
        assertThat(ApprovalPolicyEngine.decide("PENDING", null, "HOLD", NOW)).isEqualTo(ApprovalPolicyEngine.Verdict.INVALID);
    }

    @Test void cooldownSuppressesOnlyInsideTheWindow() {
        assertThat(ApprovalPolicyEngine.inCooldown(NOW, 0, NOW)).isFalse();
        assertThat(ApprovalPolicyEngine.inCooldown(null, 24, NOW)).isFalse();
        assertThat(ApprovalPolicyEngine.inCooldown(NOW.minus(Duration.ofHours(1)), 24, NOW)).isTrue();
        assertThat(ApprovalPolicyEngine.inCooldown(NOW.minus(Duration.ofHours(24)), 24, NOW)).isFalse();
    }

    @Test void resumeAllowsAFreshHumanRunAndRefusesTheRest() {
        var fresh = NOW.minus(ApprovalPolicyEngine.RESUME_WINDOW);
        var late = fresh.minusSeconds(1);
        assertThat(ApprovalPolicyEngine.resume(null, "input_required", null, fresh, NOW)).isEqualTo(ApprovalPolicyEngine.Resume.NOT_FOUND);
        assertThat(ApprovalPolicyEngine.resume("DONE", "input_required", null, fresh, NOW)).isEqualTo(ApprovalPolicyEngine.Resume.NOT_RESUMABLE);
        assertThat(ApprovalPolicyEngine.resume("SUSPENDED", "approval", null, fresh, NOW)).isEqualTo(ApprovalPolicyEngine.Resume.NOT_RESUMABLE);
        assertThat(ApprovalPolicyEngine.resume("SUSPENDED", null, null, fresh, NOW)).isEqualTo(ApprovalPolicyEngine.Resume.NOT_RESUMABLE);
        assertThat(ApprovalPolicyEngine.resume("SUSPENDED", "handoff", NOW, fresh, NOW)).isEqualTo(ApprovalPolicyEngine.Resume.EXPIRED);
        assertThat(ApprovalPolicyEngine.resume("SUSPENDED", "input_required", NOW.minusSeconds(1), fresh, NOW))
                .isEqualTo(ApprovalPolicyEngine.Resume.EXPIRED);
        assertThat(ApprovalPolicyEngine.resume("SUSPENDED", "input_required", null, null, NOW)).isEqualTo(ApprovalPolicyEngine.Resume.DENIED);
        assertThat(ApprovalPolicyEngine.resume("SUSPENDED", "handoff", null, late, NOW)).isEqualTo(ApprovalPolicyEngine.Resume.DENIED);
        assertThat(ApprovalPolicyEngine.resume("SUSPENDED", "input_required", NOW.plusSeconds(1), fresh, NOW))
                .isEqualTo(ApprovalPolicyEngine.Resume.ALLOW);
        assertThat(ApprovalPolicyEngine.resumeWindowOpen(null, NOW)).isFalse();
        assertThat(ApprovalPolicyEngine.resumeWindowOpen(late, NOW)).isFalse();
        assertThat(ApprovalPolicyEngine.resumeWindowOpen(fresh, NOW)).isTrue();
    }

    @Test void parsesPublicIdsAndRejectsTheRest() {
        assertThat(ApprovalPolicyEngine.parseId("ap_12")).isEqualTo(12);
        assertThat(ApprovalPolicyEngine.parseId("12")).isEqualTo(12);
        assertThat(ApprovalPolicyEngine.publicId(12)).isEqualTo("ap_12");
        assertThatThrownBy(() -> ApprovalPolicyEngine.parseId(null)).isInstanceOf(KeelException.class)
                .extracting(e -> ((KeelException) e).code()).isEqualTo(ErrorCode.SERVER_NOT_FOUND);
        assertThatThrownBy(() -> ApprovalPolicyEngine.parseId("  ")).isInstanceOf(KeelException.class);
        assertThatThrownBy(() -> ApprovalPolicyEngine.parseId("ap_0")).isInstanceOf(KeelException.class);
        assertThatThrownBy(() -> ApprovalPolicyEngine.parseId("ap_")).isInstanceOf(KeelException.class);
        assertThatThrownBy(() -> ApprovalPolicyEngine.parseId("nope")).isInstanceOf(KeelException.class);
    }

    @Test void normalizesSubjectRiskEnvAndRunId() {
        ApprovalPolicyEngine.checkSubject("tool.call");
        assertThatThrownBy(() -> ApprovalPolicyEngine.checkSubject("other")).isInstanceOf(KeelException.class)
                .extracting(e -> ((KeelException) e).code()).isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
        assertThat(ApprovalPolicyEngine.risk(null)).isEqualTo("HIGH");
        assertThat(ApprovalPolicyEngine.risk(" mid ")).isEqualTo("MID");
        assertThatThrownBy(() -> ApprovalPolicyEngine.risk("urgent")).isInstanceOf(KeelException.class);
        assertThat(ApprovalPolicyEngine.env(null)).isEqualTo("prod");
        assertThat(ApprovalPolicyEngine.env("dev")).isEqualTo("dev");
        assertThat(ApprovalPolicyEngine.env("test")).isEqualTo("test");
        assertThatThrownBy(() -> ApprovalPolicyEngine.env("local")).isInstanceOf(KeelException.class);
        assertThat(ApprovalPolicyEngine.scope(null)).isEmpty();
        assertThat(ApprovalPolicyEngine.scope(" ")).isEmpty();
        assertThat(ApprovalPolicyEngine.scope("all")).isEmpty();
        assertThat(ApprovalPolicyEngine.scope("staging")).isEqualTo("staging");
        assertThatThrownBy(() -> ApprovalPolicyEngine.scope("local")).isInstanceOf(KeelException.class);
        assertThat(ApprovalPolicyEngine.runId("run_1")).isTrue();
        assertThat(ApprovalPolicyEngine.runId(null)).isFalse();
        assertThat(ApprovalPolicyEngine.runId("bad/id")).isFalse();
        assertThat(ApprovalPolicyEngine.runId("a".repeat(65))).isFalse();
    }
}
