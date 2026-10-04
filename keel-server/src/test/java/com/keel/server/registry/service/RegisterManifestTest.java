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
                .isEqualTo("http://127.0.0.1:8080/builtin/agents/echo");
        assertThat(manifest.path("spec").path("models").path("budget").path("dailyCny").asInt()).isEqualTo(30);
        assertThat(manifest.path("spec").path("eval").path("dataset").asText()).isEqualTo("echo/smoke");
        assertThat(RegisterManifest.missingExternal("KEEL_JWKS_BASE 未配置")).isTrue();
        assertThat(RegisterManifest.missingExternal("名称冲突")).isFalse();
    }
}
