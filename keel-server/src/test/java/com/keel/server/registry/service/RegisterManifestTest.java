package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterManifestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void rawManifestKeepsToolAndKnowledgeSelectionsForProvisioning() throws Exception {
        var manifest = json.readTree("""
                {"apiVersion":"keel/v1","metadata":{"name":"qa-lifecycle-1016"},
                 "spec":{"kind":"agent","tools":[{"name":"shift.policy"},{"name":"local.lookup"}],
                         "knowledge":[{"kb":"平台基准库·v1"}]}}
                """);
        var selection = LifecycleService.selectionForm(json, manifest, java.util.Set.of("shift.policy"));
        assertThat(selection.path("tools").get(0).asText()).isEqualTo("shift.policy");
        assertThat(selection.path("tools").size()).isEqualTo(1);
        assertThat(selection.path("knowledgeBases").get(0).asText()).isEqualTo("平台基准库·v1");
    }

    @Test void newEchoUsesItsOwnServiceAndExplicitEndpointIsHonored() throws Exception {
        var form = json.readTree("""
                {"name":"qa-agent","template":"echo","language":"python"}
                """);
        assertThat(RegisterManifest.endpoint(form, 8080)).isEqualTo("http://qa-agent.agents.svc:8000");
        ((com.fasterxml.jackson.databind.node.ObjectNode) form).put("endpoint", "http://qa-service:8000");
        assertThat(RegisterManifest.endpoint(form, 8080)).isEqualTo("http://qa-service:8000");
        assertThat(RegisterManifest.render(form, 8080).yaml()).contains("endpoint: http://qa-service:8000", "template: echo");
        assertThat(RegisterManifest.toNode(json, form, 8080).path("spec").path("runtime").path("template").asText()).isEqualTo("echo");
    }

    @Test void echoTemplatePointsAtTheBuiltinEndpointAndKeepsTheCnyBudget() throws Exception {
        var form = json.readTree("""
                {"name":"echo","displayName":"回声","ownerOrg":"研发效能组","ownerUser":"amy","template":"echo","models":{"default":"qwen-plus","dailyBudgetCny":30}}
                """);
        var manifest = RegisterManifest.toNode(json, form, 8080);
        assertThat(manifest.path("spec").path("runtime").path("endpoint").asText())
                .isEqualTo("http://echo-agent.keel-system.svc.cluster.local:8000");
        assertThat(manifest.path("spec").path("models").path("budget").path("dailyCny").asInt()).isEqualTo(30);
        assertThat(manifest.path("spec").path("eval").path("dataset").asText()).isEqualTo("echo/smoke");
        assertThat(manifest.path("spec").path("prompts").path("items").get(0).path("name").asText()).isEqualTo("answer");
        var categorized = json.readTree("""
                {"name":"echo","displayName":"回声","ownerOrg":"研发效能组","ownerUser":"amy","category":"dev","template":"echo"}
                """);
        assertThat(RegisterManifest.toNode(json, categorized, 8080).path("metadata").path("category").asText()).isEqualTo("dev");
        assertThat(RegisterManifest.render(categorized, 8080).yaml()).contains("category: dev");
        assertThat(RegisterManifest.missingExternal("KEEL_JWKS_BASE 未配置")).isTrue();
        assertThat(RegisterManifest.missingExternal("名称冲突")).isFalse();
    }

    @Test void selectedKnowledgeIsWrittenAsKbEntries() throws Exception {
        var form = json.readTree("""
                {"name":"qa-agent","displayName":"问答","ownerOrg":"研发效能组","ownerUser":"amy","knowledgeBases":["demo","产品知识库"]}
                """);
        var manifest = RegisterManifest.toNode(json, form, 8080, java.util.List.of(), java.util.List.of("demo", "产品知识库"));
        assertThat(manifest.path("spec").path("knowledge").get(0).path("kb").asText()).isEqualTo("demo");
        assertThat(manifest.path("spec").path("knowledge").get(1).path("kb").asText()).isEqualTo("产品知识库");
        var yaml = RegisterManifest.render(form, 8080, java.util.List.of(), java.util.List.of("demo", "产品知识库")).yaml();
        assertThat(yaml).contains("knowledge:", "kb: demo", "kb: \"产品知识库\"");
        assertThat(manifest.path("spec").path("runtime").path("endpoint").asText())
                .isEqualTo("http://kb-bench.agents.svc.cluster.local:8000");
    }

    @Test void promptTypeDefaultsToTextAndHonorsChat() throws Exception {
        var text = json.readTree("""
                {"name":"qa-agent"}
                """);
        assertThat(RegisterManifest.toNode(json, text, 8080).path("spec").path("prompts").path("items").get(0).path("type").asText())
                .isEqualTo("text");
        assertThat(RegisterManifest.render(text, 8080).yaml()).contains("type: text");
        var chat = json.readTree("""
                {"name":"qa-agent","promptType":"chat"}
                """);
        assertThat(RegisterManifest.toNode(json, chat, 8080).path("spec").path("prompts").path("items").get(0).path("type").asText())
                .isEqualTo("chat");
        assertThat(RegisterManifest.render(chat, 8080).yaml()).contains("type: chat");
        var other = json.readTree("""
                {"name":"qa-agent","promptType":"image"}
                """);
        assertThat(RegisterManifest.toNode(json, other, 8080).path("spec").path("prompts").path("items").get(0).path("type").asText())
                .isEqualTo("text");
    }
}
