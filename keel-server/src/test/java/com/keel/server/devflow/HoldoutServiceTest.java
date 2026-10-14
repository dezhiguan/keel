package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.release.ReleaseGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldoutServiceTest {
    @Test
    void acceptedCasesAreSplitAndOnlyTheCiClientCanReadThem() {
        var hidden = HoldoutRules.split("DF-0001", cases(10), 30);
        assertThat(hidden).hasSize(3);
        assertThat(HoldoutRules.split("DF-0001", cases(10), 30)).extracting(DevflowTypes.SeedCase::caseId)
                .containsExactlyElementsOf(hidden.stream().map(DevflowTypes.SeedCase::caseId).toList());

        var ledger = new MemoryDevflowLedger();
        var store = new MemoryHoldoutStore();
        ledger.insert(job("DF-0001"));
        var service = new DevflowService(ledger, new RecordingAudit(), store);
        var ids = cases(10).stream().map(DevflowTypes.SeedCase::caseId).toList();
        var result = service.acceptSeeds("DF-0001", ids, cases(10), "官德志");
        assertThat(result.holdoutCount()).isEqualTo(3);
        assertThat(result.acceptedAgentCount()).isEqualTo(10);
        assertThat(store.cases("DF-0001")).extracting(DevflowTypes.SeedCase::caseId)
                .containsExactlyInAnyOrderElementsOf(hidden.stream().map(DevflowTypes.SeedCase::caseId).toList());

        var holdout = new HoldoutService(store, "keel-gate");
        assertThatThrownBy(() -> holdout.read("DF-0001", "meta-agent"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.AUTH_CONSOLE_FORBIDDEN);
        assertThatThrownBy(() -> new HoldoutService(store, "").read("DF-0001", "keel-gate"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.AUTH_CONSOLE_FORBIDDEN);
        @SuppressWarnings("unchecked")
        var body = (List<DevflowTypes.SeedCase>) holdout.read("DF-0001", "keel-gate").get("cases");
        assertThat(body).hasSize(3);

        assertThatThrownBy(() -> holdout.submit("DF-0001", List.of(new HoldoutRules.TagScore("safety", 1.0, 1)), "keel-gate"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);

        assertThatThrownBy(() -> holdout.submit("DF-0001", List.of(new HoldoutRules.TagScore("safety", 0.5, 3)), "keel-gate"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.DEVFLOW_HOLDOUT_FAILED);
        assertThat(store.result("DF-0001")).isFalse();
        holdout.submit("DF-0001", List.of(new HoldoutRules.TagScore("safety", 0.9, 3)), "keel-gate");
        assertThat(store.result("DF-0001")).isTrue();
    }

    @Test
    void idsWithoutBodiesDoNotCreateHiddenCases() {
        var ledger = new MemoryDevflowLedger();
        ledger.insert(job("DF-0002"));
        var result = new DevflowService(ledger, new RecordingAudit()).acceptSeeds("DF-0002", List.of("c1", "c2"), "官德志");
        assertThat(result.holdoutCount()).isZero();
        assertThat(result.acceptedAgentCount()).isEqualTo(2);
    }

    @Test
    void aProducedAgentNeedsAnApprovedMerge() {
        assertThat(ReleaseGuard.rejection(null, false)).isNull();
        assertThat(ReleaseGuard.rejection("DF-0001", true)).isNull();
        assertThat(ReleaseGuard.rejection("DF-0001", false)).contains("git.pr.merge");
    }

    @Test
    void aProducedAgentCannotUseAnArbitraryGateRunIdOrAStaleCommit() throws Exception {
        var state = new ObjectMapper().readTree("""
                {"phase":"RELEASING","delivery":{"sha":"abc"},"mergeSha":"merged",
                 "humanReview":{"approved":true},"modelReview":{"approved":true},
                 "gate":{"passed":true,"runId":"staging-gate","env":"staging","sha":"abc","image":"stage-image"},
                 "prodGate":{"passed":true,"runId":"gate-1","env":"prod","sha":"merged","image":"prod-image"}}
                """);
        assertThat(ReleaseGuard.evidenceRejection(state, "prod", "gate-1", "prod-image", true, true)).isNull();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state).remove("prodGate");
        assertThat(ReleaseGuard.evidenceRejection(state, "prod", "staging-gate", "stage-image", true, true)).contains("门禁");
        ((com.fasterxml.jackson.databind.node.ObjectNode) state).set("prodGate", new ObjectMapper().readTree("""
                {"passed":true,"runId":"gate-1","env":"prod","sha":"merged","image":"prod-image"}
                """));
        assertThat(ReleaseGuard.evidenceRejection(state, "prod", "gate-2", "prod-image", true, true)).contains("门禁");
        assertThat(ReleaseGuard.evidenceRejection(state, "prod", "gate-1", "other-image", true, true)).contains("门禁");
        assertThat(ReleaseGuard.evidenceRejection(state, "prod", "gate-1", "prod-image", false, true)).contains("隐藏考题");
        assertThat(ReleaseGuard.evidenceRejection(state, "prod", "gate-1", "prod-image", true, false)).contains("H4");
        ((com.fasterxml.jackson.databind.node.ObjectNode) state).put("mergeSha", "changed");
        assertThat(ReleaseGuard.evidenceRejection(state, "prod", "gate-1", "prod-image", true, true)).contains("候选提交");
    }

    private static DevflowTypes.Job job(String id) {
        return new DevflowTypes.Job(id, "标题", "BIZ", "CREATE", "AUTO", "WAIT", "H2",
                "weekly-report", "dev-lead", "tool-agent", 0, 80, 0, 3, null, false, null, null,
                "官德志", "研发效能组", "目标", List.of(), List.of(), new DevflowTypes.Seed(30, 0, 0), List.of());
    }

    private static List<DevflowTypes.SeedCase> cases(int count) {
        var cases = new ArrayList<DevflowTypes.SeedCase>();
        for (int i = 0; i < count; i++) {
            cases.add(new DevflowTypes.SeedCase("c" + i, Map.of("text", "q" + i), Map.of("text", "a" + i), List.of("safety")));
        }
        return cases;
    }
}
