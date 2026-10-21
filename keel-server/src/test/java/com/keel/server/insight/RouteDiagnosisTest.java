package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.common.KeelException;
import com.keel.server.insight.controller.InsightController;
import com.keel.server.registry.service.AgentRegistryService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RouteDiagnosisTest {
    @Test void defaultUsesManifestForTheKeysEnvironmentAndExplicitOverrideIsKept() {
        var json=new ObjectMapper();
        var registry=new AgentRegistryService(null,null,null,null,json,null) {
            @Override public String defaultModelForEnv(String agent,String env) {
                assertThat(agent).isEqualTo("personal-assistant");
                return "staging".equals(env)?"chat-default":"";
            }
        };
        var costs=new CostService(null) {
            @Override public JsonNode diagnoseRoute(String alias,String model) {
                return json.createObjectNode().put("requestedModel",model);
            }
        };
        var controller=new InsightController(null,null,costs,null,registry,null);
        var normal=controller.diagnoseRoute(Map.of("alias","personal-assistant-staging")).data();
        assertThat(normal.path("requestedModel").asText()).isEqualTo("chat-default");
        assertThat(normal.path("modelSource").asText()).isEqualTo("manifest");
        var explicit=controller.diagnoseRoute(Map.of("alias","personal-assistant-prod","model","qwen-plus")).data();
        assertThat(explicit.path("requestedModel").asText()).isEqualTo("qwen-plus");
        assertThat(explicit.path("modelSource").asText()).isEqualTo("override");
        assertThatThrownBy(()->controller.diagnoseRoute(Map.of("alias","personal-assistant-prod")))
                .isInstanceOf(KeelException.class).hasMessageContaining("未登记默认模型");
        assertThatThrownBy(()->controller.diagnoseRoute(Map.of("alias","bad-key"))).isInstanceOf(KeelException.class);
    }
}
