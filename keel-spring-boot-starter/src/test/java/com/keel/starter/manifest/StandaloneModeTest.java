package com.keel.starter.manifest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.keel.common.model.AgentManifest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class StandaloneModeTest {
    private final ManifestLoader loader = new ManifestLoader(new DefaultResourceLoader());
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    @Test
    void standaloneStartsWithoutGatewayAuditOrRegistration() throws Exception {
        assertThatCode(() -> loader.requireEnvironment(manifest(), env(
                "KEEL_MODE", "standalone",
                "KEEL_ENV", "dev",
                "KEEL_LLM_BASE_URL", "http://127.0.0.1:9/v1")::get)).doesNotThrowAnyException();
    }

    @Test
    void productionProfileRejectsStandalone() throws Exception {
        assertThatThrownBy(() -> loader.requireEnvironment(manifest(), env(
                "KEEL_MODE", "standalone", "KEEL_ENV", "prod",
                "KEEL_LLM_BASE_URL", "http://127.0.0.1:9/v1")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production profile");
        assertThatThrownBy(() -> loader.requireEnvironment(manifest(), env(
                "KEEL_MODE", "standalone", "KEEL_ENV", "dev",
                "SPRING_PROFILES_ACTIVE", "local,prod",
                "KEEL_LLM_BASE_URL", "http://127.0.0.1:9/v1")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production profile");
    }

    @Test
    void governedModeStillRequiresTheVirtualKeyAndAuditEndpoint() throws Exception {
        assertThatThrownBy(() -> loader.requireEnvironment(manifest(), env(
                "KEEL_LLM_BASE_URL", "http://127.0.0.1:9/v1")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KEEL_LLM_KEY");
    }

    private AgentManifest manifest() throws Exception {
        return yaml.readValue("""
                apiVersion: keel/v1
                kind: Agent
                metadata:
                  name: demo-agent
                spec:
                  runtime:
                    endpoint: http://127.0.0.1:8000
                  models:
                    default: qwen-plus
                  tools:
                    - name: echo
                """, AgentManifest.class);
    }

    private static Map<String, String> env(String... pairs) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }
}
