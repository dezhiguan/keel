package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WizardToolsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<WizardTools.Row> catalog = List.of(
            new WizardTools.Row("git.branch.push", "ONLINE", "WRITE", "LOW", "v1"),
            new WizardTools.Row("git.pr.merge", "ONLINE", "WRITE", "HIGH", "v1"),
            new WizardTools.Row("alarm_query", "RETIRED", "READ", "LOW", "v2"));

    @Test void lowRiskToolIsCopiedFromTheCatalog() throws Exception {
        var form = json.readTree("""
                {"name":"echo","tools":["git.branch.push"]}
                """);
        var tools = WizardTools.resolve(form, catalog, Set.of());
        assertThat(tools).containsExactly(new WizardTools.Spec("git.branch.push", "write", "low", "v1"));
        var manifest = RegisterManifest.toNode(json, form, 8080, tools);
        assertThat(manifest.path("spec").path("tools").get(0).path("access").asText()).isEqualTo("write");
        assertThat(manifest.path("spec").path("tools").get(0).path("approval").isMissingNode()).isTrue();
        assertThat(RegisterManifest.render(form, 8080, tools).yaml()).contains("name: git.branch.push").contains("access: write");
    }

    @Test void highRiskRequiresAKnownPolicy() throws Exception {
        var form = json.readTree("""
                {"name":"echo","tools":["git.pr.merge"]}
                """);
        assertThatThrownBy(() -> WizardTools.resolve(form, catalog, Set.of("9")))
                .isInstanceOf(KeelException.class)
                .hasMessageContaining("审批策略");
        var chosen = json.readTree("""
                {"name":"echo","tools":["git.pr.merge"],"approvalPolicyId":"9"}
                """);
        var tools = WizardTools.resolve(chosen, catalog, Set.of("9"));
        assertThat(tools.getFirst().high()).isTrue();
        assertThat(RegisterManifest.toNode(json, chosen, 8080, tools).path("spec").path("tools").get(0).path("approval").asText())
                .isEqualTo("required");
    }

    @Test void unknownAndRetiredNamesAreRejected() throws Exception {
        var missing = json.readTree("""
                {"tools":["not.a.tool"]}
                """);
        var retired = json.readTree("""
                {"tools":["alarm_query"]}
                """);
        assertThatThrownBy(() -> WizardTools.resolve(missing, catalog, Set.of()))
                .isInstanceOf(KeelException.class)
                .hasMessageContaining("不在注册表里");
        assertThatThrownBy(() -> WizardTools.resolve(retired, catalog, Set.of()))
                .isInstanceOf(KeelException.class)
                .hasMessageContaining("已下线");
    }

    @Test void aFullManifestIsLeftAlone() throws Exception {
        var form = json.readTree("""
                {"apiVersion":"keel/v1","tools":["git.branch.push"]}
                """);
        assertThat(WizardTools.resolve(form, catalog, Set.of())).isEmpty();
    }
}
