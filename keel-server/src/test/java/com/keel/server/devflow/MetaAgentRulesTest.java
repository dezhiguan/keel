package com.keel.server.devflow;

import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MetaAgentRulesTest {
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
    @Test void acceptsOnlyTheSevenPrototypeRolesAndTheirTemplates() {
        for (var name : List.of("dev-lead", "spec-agent", "dev-agent", "eval-agent", "code-review", "ci-doctor", "release-agent")) {
            String template = name.equals("dev-lead") ? "supervisor" : List.of("spec-agent", "dev-agent").contains(name) ? "graph-agent" : "tool-agent";
            assertThatCode(() -> MetaAgentExecution.validate(draft(name, template, "DEV", "CREATE", 0, 7.0))).doesNotThrowAnyException();
            assertThatThrownBy(() -> MetaAgentExecution.validate(draft(name, "chat-rag", "DEV", "CREATE", 0, 7.0))).isInstanceOf(KeelException.class);
        }
        assertThatThrownBy(() -> MetaAgentExecution.validate(draft("qa-meta-loop", "tool-agent", "DEV", "CREATE", 0, 7.0))).isInstanceOf(KeelException.class);
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
                "dev-lead", List.of("devflow.job.read", "devflow.stage.report"),
                "spec-agent", List.of("devflow.catalog.search"),
                "dev-agent", List.of("sandbox.run", "git.repo.create", "git.pr.open"),
                "eval-agent", List.of("sandbox.run"),
                "code-review", List.of("git.pr.diff", "git.pr.comment"),
                "ci-doctor", List.of("ci.log.fetch", "keel.gate.report.read"),
                "release-agent", List.of("ci.workflow.dispatch", "git.pr.merge"));
        return new DevflowTypes.Draft("QA", name, layer, kind, "COLLAB", "生成需求文档", template, budget,
                seeds, tools.getOrDefault(name, List.of()), List.of(), "QA", "研发人员", "需求到文档", "包含目标", true);
    }
}
