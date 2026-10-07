package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterManifestTest {
    private final ObjectMapper json = new ObjectMapper();

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
}
