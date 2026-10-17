package com.keel.server.devflow;

import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MetaAgentRulesTest {
    @Test void failedGenerationCanReceiveClarificationOnlyWithinTheExistingUnmergedWindow() throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"phase":"FAILED","delivery":{"sha":"abc"},
                 "repair":{"maxAttempts":3,"attempt":2,"failedSha":"abc"},
                 "sandboxAttempts":[{"sha":"abc","status":"PASS","report":"sha=abc snapshot=ok tests=PASS\\n"}]}
                """);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isTrue();
        assertThat(MetaAgentExecution.canStartClarifiedRepairWindow(state)).isFalse();
        for (String phase : List.of("DELIVERING", "QUEUED", "RELEASING", "WATCHING")) {
            state.put("phase", phase);
            assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        }
        state.put("phase", "FAILED").put("sandboxStatus", "FAILED");
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        state.remove("sandboxStatus");
        state.put("h4ApprovalId", "approval");
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        state.remove("h4ApprovalId");
        state.put("mergeSha", "merged");
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        state.remove("mergeSha");
        var repair = (com.fasterxml.jackson.databind.node.ObjectNode) state.path("repair");
        repair.put("failedSha", "stale");
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        repair.put("failedSha", "abc").put("attempt", 3);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        repair.put("attempt", 2).put("maxAttempts", 1);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        repair.put("maxAttempts", 3);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isTrue();
    }
    @Test void passedSandboxRetainsFeedbackAllowanceAndLegacyPassNeedsExplicitNewWindow() throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"phase":"WAITING_REVIEW","sandboxStatus":"PASS","repairWindow":{"maxAttempts":3},"sandboxAttempts":[{},{}]}
                """);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isTrue();
        assertThat(MetaAgentExecution.canStartClarifiedRepairWindow(state)).isFalse();
        state.put("phase", "STAGING_READY");
        state.putObject("delivery").put("sha", "abc");
        state.putObject("pipeline").put("status", "FAILED").put("env", "staging").put("evaluationFailed", true).put("sha", "abc");
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isTrue();
        state.put("phase", "RELEASING");
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        state.put("phase", "WAITING_REVIEW");
        state.remove("repairWindow");
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        assertThat(MetaAgentExecution.canStartClarifiedRepairWindow(state)).isTrue();
        state.put("manualRepairWindows", 1);
        assertThat(MetaAgentExecution.canStartClarifiedRepairWindow(state)).isFalse();
    }
    @Test void onlyExplicitClarificationCanOpenOneNewFailedWindowWithinAbsoluteLimit() throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"phase":"FAILED","sandboxStatus":"FAILED","repair":{"maxAttempts":1},
                "delivery":{"sha":"abc"},"sandboxAttempts":[{"sha":"abc","report":"sha=abc snapshot=ok tests=FAIL"}]}
                """);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        assertThat(MetaAgentExecution.canStartClarifiedRepairWindow(state)).isTrue();
        state.put("manualRepairWindows", 1);
        assertThat(MetaAgentExecution.canStartClarifiedRepairWindow(state)).isFalse();
        state.put("manualRepairWindows", 0).put("phase", "RELEASING");
        assertThat(MetaAgentExecution.canStartClarifiedRepairWindow(state)).isFalse();
    }
    @Test void developerFeedbackCannotExtendOrReopenAnExhaustedRepairWindow() throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"phase":"WAITING_REVIEW","sandboxStatus":"PASS","repair":{"maxAttempts":3},"sandboxAttempts":[{},{}]}
                """);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isTrue();
        state.withArray("sandboxAttempts").addObject();
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("repair")).put("maxAttempts", 99);
        assertThat(MetaAgentExecution.canAcceptRepairFeedback(state)).isFalse();
    }
    @Test void adapterUpdateUsesDeliveryVersionAndNeverRunsAfterCodeReview() throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"phase":"WAITING_REVIEW","sandboxStatus":"PASS","delivery":{"sha":"abc"},
                "draft":{"manifest":{"spec":{"delegates":["child"]}}},"sandboxAttempts":[{},{},{},{},{},{},{},{},{},{},{}]}
                """);
        assertThat(MetaAgentExecution.canRefreshRuntimeAdapter(state)).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("delivery")).put("runtimeAdapterVersion", "registered-env-v1");
        assertThat(MetaAgentExecution.canRefreshRuntimeAdapter(state)).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("delivery")).put("runtimeAdapterVersion", "prompt-boundary-v2");
        assertThat(MetaAgentExecution.canRefreshRuntimeAdapter(state)).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("delivery")).remove("runtimeAdapterVersion");
        state.put("phase", "STAGING_READY");
        assertThat(MetaAgentExecution.canRefreshRuntimeAdapter(state)).isFalse();
    }
    @Test void promptBoundaryUpgradeCoversToolAgentsAndRespectsAbsoluteLimit() throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"phase":"WAITING_REVIEW","sandboxStatus":"PASS","delivery":{"runtimeAdapterVersion":"registered-env-v1"},
                "requirement":{"tools":["ci.log.fetch","git.pr.diff"]},"sandboxAttempts":[{}]}
                """);
        assertThat(MetaAgentExecution.canRefreshRuntimeAdapter(state)).isTrue();
        while (state.path("sandboxAttempts").size() < 20) state.withArray("sandboxAttempts").addObject();
        assertThat(MetaAgentExecution.canRefreshRuntimeAdapter(state)).isFalse();
    }
    @Test void gateRepairRequiresExactFailedStagingEvaluationAndStopsAfterOneWindow() throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"phase":"STAGING_READY","delivery":{"sha":"abc"},"pipeline":{"env":"staging",
                "sha":"abc","status":"FAILED","evaluationFailed":true},"sandboxAttempts":[{}]}
                """);
        assertThat(MetaAgentExecution.canRepairStagingGate(state)).isTrue();
        state.put("gateRepairRounds", 1);
        assertThat(MetaAgentExecution.canRepairStagingGate(state)).isFalse();
        state.put("gateRepairRounds", 0).put("phase", "RELEASING");
        assertThat(MetaAgentExecution.canRepairStagingGate(state)).isFalse();
        state.put("phase", "STAGING_READY");
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("pipeline")).put("evaluationFailed", false);
        assertThat(MetaAgentExecution.canRepairStagingGate(state)).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("pipeline")).put("evaluationFailed", true).put("sha", "old");
        assertThat(MetaAgentExecution.canRepairStagingGate(state)).isFalse();
    }
    @Test void reviewSessionCanAutoRepairSeveralCommitsButStopsAtItsCeiling() throws Exception {
        var json = new ObjectMapper();
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"sandboxAttemptCeiling":12,"sandboxStatus":"FAILED","sandboxAttempts":[{},{},{},{},{},{},{},{}]}
                """);
        assertThat(MetaAgentExecution.sandboxAttemptLimit(state)).isEqualTo(12);
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(state)).isFalse();
        for (int i = 0; i < 4; i++) state.withArray("sandboxAttempts").addObject();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(state)).isTrue();
    }
    @Test void pipelineRequiresExactReviewedCandidateAndH4ForProd() throws Exception {
        var json = new ObjectMapper();
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"phase":"STAGING_READY","sandboxStatus":"PASS","delivery":{"sha":"abc"},
                 "humanReview":{"approved":true,"headSha":"abc"},"modelReview":{"approved":true,"sha":"abc"}}
                """);
        assertThatCode(() -> MetaAgentExecution.validatePipelineState(state, "staging")).doesNotThrowAnyException();
        assertThatThrownBy(() -> MetaAgentExecution.validatePipelineState(state, "prod")).isInstanceOf(KeelException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("humanReview")).put("headSha", "stale");
        assertThatThrownBy(() -> MetaAgentExecution.validatePipelineState(state, "staging")).isInstanceOf(KeelException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("humanReview")).put("headSha", "abc");
        state.put("phase", "RELEASING").put("mergeSha", "a".repeat(40));
        assertThatCode(() -> MetaAgentExecution.validatePipelineState(state, "prod")).doesNotThrowAnyException();
    }
    @Test void infrastructureRefreshHasItsOwnBoundedCounter() {
        assertThat(MetaAgentExecution.canRefreshInfrastructure(0)).isTrue();
        assertThat(MetaAgentExecution.canRefreshInfrastructure(1)).isTrue();
        assertThat(MetaAgentExecution.canRefreshInfrastructure(2)).isFalse();
        assertThat(MetaAgentExecution.canRefreshInfrastructure(-1)).isFalse();
    }
    @Test void failedInfrastructureDeliveryCanRetryWithoutConsumingAnotherRefresh() throws Exception {
        var json = new ObjectMapper();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{}],"repair":{"infrastructureOnly":true}}
                """))).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{}],"repair":{"infrastructureOnly":false}}
                """))).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{}],"repair":{"infrastructureOnly":false}}
                """))).isTrue();
    }
    @Test void infrastructureRefreshesDoNotConsumeFiveFunctionalSandboxRounds() throws Exception {
        var json = new ObjectMapper();
        var fiveAttempts = json.readTree("""
                {"infrastructureRefreshes":2,"sandboxStatus":"FAILED",
                 "sandboxAttempts":[{},{},{},{},{}]}
                """);
        assertThat(MetaAgentExecution.sandboxAttemptLimit(fiveAttempts)).isEqualTo(7);
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(fiveAttempts)).isFalse();
        var sha = "a".repeat(40);
        var report = "sha=" + sha + " snapshot=ok tests=FAIL\nfailed";
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, sha, 5, 7)).isTrue();
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, sha, 7, 7)).isFalse();
        var sevenAttempts = json.readTree("""
                {"infrastructureRefreshes":2,"sandboxStatus":"FAILED",
                 "sandboxAttempts":[{},{},{},{},{},{},{}]}
                """);
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(sevenAttempts)).isTrue();
    }
    @Test void failedTrustedContractAllowsOneBoundedRecovery() throws Exception {
        var json = new ObjectMapper();
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"phase":"FAILED","sandboxStatus":"FAILED","infrastructureRefreshes":1,
                 "delivery":{"sha":"abc"},
                 "sandboxAttempts":[{},{},{},{},{},
                   {"sha":"abc","report":"sha=abc snapshot=ok tests=FAIL\\ntests/test_platform_contract.py::test_normal"}]}
                """);
        assertThat(MetaAgentExecution.canRetryTrustedContract(state)).isTrue();
        state.put("infrastructureRefreshes", 2);
        assertThat(MetaAgentExecution.canRetryTrustedContract(state)).isFalse();
        state.put("infrastructureRefreshes", 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("delivery")).put("sha", "different");
        assertThat(MetaAgentExecution.canRetryTrustedContract(state)).isFalse();
    }
    @Test void reviewRepairCanRetryTransportFailureButNotFailedSixthSandbox() throws Exception {
        var json = new ObjectMapper();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{}],"repair":{"reviewRepair":true}}
                """))).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{}],"sandboxStatus":"FAILED",
                 "repair":{"reviewRepair":true}}
                """))).isTrue();
    }
    @Test void ciRefreshCanRetryTransportFailureButNotFailedSeventhSandbox() throws Exception {
        var json = new ObjectMapper();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{}],"repair":{"ciRefresh":true}}
                """))).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{},{}],"sandboxStatus":"FAILED",
                 "repair":{"ciRefresh":true}}
                """))).isTrue();
        assertThat(MetaAgentExecution.canRefreshCandidateCi(json.readTree("""
                {"reviewRepairRounds":1,"s4RepairRounds":1}
                """), 10)).isTrue();
        assertThat(MetaAgentExecution.canRefreshCandidateCi(json.readTree("""
                {"reviewRepairRounds":1,"s4RepairRounds":1}
                """), 11)).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{},{}],"repair":{"ciRefresh":true,"attempt":10}}
                """))).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{},{},{}],"sandboxStatus":"FAILED",
                 "repair":{"ciRefresh":true,"attempt":10}}
                """))).isTrue();
    }
    @Test void s4RepairRequiresFailedReviewOnCurrentApprovedShaAndStopsAfterOneRound() throws Exception {
        var json = new ObjectMapper();
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"delivery":{"sha":"aaaa"},"humanReview":{"approved":true,"headSha":"aaaa"},
                 "modelReview":{"sha":"aaaa","approved":false,
                   "findings":[{"severity":"HIGH"}]},"s4RepairRounds":0}
                """);
        assertThat(MetaAgentExecution.canRepairS4(state, 2)).isTrue();
        assertThat(MetaAgentExecution.canRepairS4(state, 9)).isTrue();
        assertThat(MetaAgentExecution.canRepairS4(state, 1)).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("modelReview").path("findings").get(0))
                .put("severity", "LOW");
        assertThat(MetaAgentExecution.canRepairS4(state, 9)).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("modelReview").path("findings").get(0))
                .put("severity", "HIGH");
        state.put("s4RepairRounds", 1);
        assertThat(MetaAgentExecution.canRepairS4(state, 9)).isFalse();
        state.put("s4RepairRounds", 0);
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("modelReview")).put("sha", "bbbb");
        assertThat(MetaAgentExecution.canRepairS4(state, 9)).isFalse();
        var transport = json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{}],"repair":{"s4Repair":true}}
                """);
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(transport)).isFalse();
        var failed = json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{},{}],"sandboxStatus":"FAILED",
                 "repair":{"s4Repair":true}}
                """);
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(failed)).isTrue();
    }
    @Test void sdkContractRepairIsBoundedToEleventhCandidate() throws Exception {
        var json = new ObjectMapper();
        var state = json.readTree("""
                {"s4RepairRounds":1,"reviewRepairRounds":1,"sandboxStatus":"PASS"}
                """);
        assertThat(MetaAgentExecution.canRepairSdkContract(state, 11)).isTrue();
        assertThat(MetaAgentExecution.canRepairSdkContract(state, 12)).isFalse();
        assertThat(MetaAgentExecution.canRepairSdkContract(json.readTree("""
                {"s4RepairRounds":1,"reviewRepairRounds":1,"sandboxStatus":"FAILED"}
                """), 11)).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{},{},{}],"repair":{"sdkRepair":true,"attempt":11}}
                """))).isFalse();
        assertThat(MetaAgentExecution.sandboxLimitBlocksRetry(json.readTree("""
                {"sandboxAttempts":[{},{},{},{},{},{},{},{},{},{},{}],"sandboxStatus":"FAILED",
                 "repair":{"sdkRepair":true,"attempt":11}}
                """))).isTrue();
    }
    @Test void platformSmokeMismatchNeedsExactCurrentShaAndKnownFailure() throws Exception {
        var json = new ObjectMapper();
        var state = json.readTree("""
                {"delivery":{"sha":"aaaa"},"sandboxStatus":"FAILED","repair":{"ciRefresh":true},
                 "sandboxAttempts":[{},{},{},{},{},{},{},{},{"sha":"aaaa","status":"FAILED",
                 "report":"test_manifest_matches_the_delivered_contract\\nassert manifest.metadata.name == ROOT.name\\nAssertionError: assert 'spec-agent' == 'repo'"}]}
                """);
        assertThat(MetaAgentExecution.platformSmokeMismatch(state)).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("delivery")).put("sha", "bbbb");
        assertThat(MetaAgentExecution.platformSmokeMismatch(state)).isFalse();
    }
    @Test void refreshUsesOnlyTheThreeCandidateFiles() throws Exception {
        var json = new ObjectMapper();
        var snapshot = json.readTree("""
                {"files":[{"path":"role.py","content":"role"},{"path":"prompts/role.md","content":"prompt"},
                {"path":"tests/test_role.py","content":"test"},{"path":".github/workflows/keel.yml","content":"old"}]}
                """);
        assertThat(MetaAgentExecution.previousCandidate(json, snapshot).path("files")).hasSize(3);
        var missing = json.readTree("{\"files\":[{\"path\":\"role.py\",\"content\":\"role\"}]}");
        assertThatThrownBy(() -> MetaAgentExecution.previousCandidate(json, missing))
                .isInstanceOf(KeelException.class).hasMessageContaining("缺少修复所需文件");
    }
    @Test void sandboxRepairsOnlyActualTestFailuresBeforeFifthAttempt() {
        var sha = "a".repeat(40);
        var report = "sha=" + sha + " snapshot=ok tests=FAIL\ntest_normal failed";
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, sha, 1)).isTrue();
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, sha, 2)).isTrue();
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, sha, 3)).isTrue();
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, sha, 4)).isTrue();
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, sha, 5)).isFalse();
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", report, "b".repeat(40), 1)).isFalse();
        assertThat(MetaAgentExecution.repairableSandbox("TIMEOUT", report, sha, 1)).isFalse();
        assertThat(MetaAgentExecution.repairableSandbox("FAILED", "snapshot=invalid tests=NOT_RUN", sha, 1)).isFalse();
    }
    @Test void reviewRequiresHardcodedPromptCheck() throws Exception {
        var json = new ObjectMapper();
        var report = json.readTree("""
                {"approved":true,"checks":{"modelAccess":true,"toolAuthorization":true,"audit":true,
                "visibleCaseOverfit":true,"releaseApproval":true,"behaviorTests":true}}
                """);
        assertThatThrownBy(() -> MetaAgentExecution.validateReviewChecks(report))
                .isInstanceOf(KeelException.class).hasMessageContaining("维度不完整");
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("checks")).put("hardcodedPrompt", false);
        assertThatThrownBy(() -> MetaAgentExecution.validateReviewChecks(report))
                .isInstanceOf(KeelException.class).hasMessageContaining("分项结果冲突");
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("checks")).put("hardcodedPrompt", true);
        assertThatCode(() -> MetaAgentExecution.validateReviewChecks(report)).doesNotThrowAnyException();
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    @Test void requiresAnActualWritableAdministrator() {
        assertThatThrownBy(MetaAgentExecution::requireAdmin).isInstanceOf(KeelException.class);
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ConsolePrincipal.set(request, ConsolePrincipal.preview());
        assertThatThrownBy(MetaAgentExecution::requireAdmin).isInstanceOf(KeelException.class);
        ConsolePrincipal.set(request, ConsolePrincipal.service("meta-agent"));
        assertThatThrownBy(MetaAgentExecution::requireAdmin).isInstanceOf(KeelException.class);
        ConsolePrincipal.set(request, ConsolePrincipal.dev());
        assertThatCode(MetaAgentExecution::requireAdmin).doesNotThrowAnyException();
    }
    @Test void acceptsGeneralAgentsAndKeepsSevenPrototypeRolesLocked() {
        for (var name : List.of("dev-lead", "spec-agent", "dev-agent", "eval-agent", "code-review", "ci-doctor", "release-agent")) {
            String template = name.equals("dev-lead") ? "supervisor" : List.of("spec-agent", "dev-agent").contains(name) ? "graph-agent" : "tool-agent";
            assertThatCode(() -> MetaAgentExecution.validate(draft(name, template, "DEV", "CREATE", 0, 7.0))).doesNotThrowAnyException();
            assertThatThrownBy(() -> MetaAgentExecution.validate(draft(name, "chat-rag", "DEV", "CREATE", 0, 7.0))).isInstanceOf(KeelException.class);
        }
        for (var template : List.of("tool-agent", "chat-rag", "graph-agent")) {
            assertThatCode(() -> MetaAgentExecution.validate(draft("qa-meta-loop", template, "DEV", "CREATE", 0, 7.0))).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> MetaAgentExecution.validate(draft("qa-meta-loop", "supervisor", "DEV", "CREATE", 0, 7.0))).isInstanceOf(KeelException.class);
        var supervisor = new DevflowTypes.Draft("QA", "qa-meta-loop", "DEV", "CREATE", "COLLAB", "协调任务",
                "supervisor", 7.0, 0, List.of(), List.of(), "QA", "研发", "任务到结果", "有证据", true,
                List.of("qa-ci-investigator-1017"));
        assertThatCode(() -> MetaAgentExecution.validate(supervisor)).doesNotThrowAnyException();
        var unsupportedTool = new DevflowTypes.Draft("QA", "qa-meta-loop", "DEV", "CREATE", "COLLAB", "协调任务",
                "tool-agent", 7.0, 0, List.of("devflow.stage.report"), List.of(), "QA", "研发", "任务到结果", "有证据", true);
        assertThatThrownBy(() -> MetaAgentExecution.validate(unsupportedTool))
                .isInstanceOf(KeelException.class).hasMessageContaining("低风险只读工具");
        assertThatThrownBy(() -> MetaAgentExecution.validate(draft("qa-meta-loop", "java-spring", "DEV", "CREATE", 0, 7.0))).isInstanceOf(KeelException.class);
    }
    @Test void rejectsUnsupportedScopeAndFakeSeedUploads() {
        for (var bad : List.of(draft("BIZ", "CREATE", 0, 7.0), draft("dev", "CREATE", 0, 7.0),
                draft("DEV", "CHANGE", 0, 7.0), draft("DEV", "CREATE", 34, 7.0),
                draft("DEV", "CREATE", 0, Double.NaN))) {
            assertThatThrownBy(() -> MetaAgentExecution.validate(bad)).isInstanceOf(KeelException.class);
        }
    }
    static DevflowTypes.Draft draft(String layer, String kind, int seeds, double budget) {
        return draft("spec-agent", "graph-agent", layer, kind, seeds, budget);
    }
    static DevflowTypes.Draft draft(String name, String template, String layer, String kind, int seeds, double budget) {
        var tools = Map.of(
                "dev-lead", List.of("devflow.job.read", "devflow.stage.report", "devflow.batch.read"),
                "spec-agent", List.of("devflow.catalog.search", "devflow.stage.report"),
                "dev-agent", List.of("sandbox.run", "git.repo.create", "git.branch.push", "git.pr.open"),
                "eval-agent", List.of("devflow.job.read", "sandbox.run", "devflow.stage.report"),
                "code-review", List.of("git.pr.diff", "git.pr.comment"),
                "ci-doctor", List.of("ci.log.fetch", "keel.gate.report.read"),
                "release-agent", List.of("ci.workflow.dispatch", "keel.gate.report.read", "git.pr.merge"));
        return new DevflowTypes.Draft("QA", name, layer, kind, "COLLAB", "生成需求文档", template, budget,
                seeds, tools.getOrDefault(name, List.of()), List.of(), "QA", "研发人员", "需求到文档", "包含目标", true);
    }
}
