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
