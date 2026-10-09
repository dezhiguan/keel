package com.keel.server.registry.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentVersion;
import com.keel.server.registry.model.enums.AgentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDetailTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void storedManifestBecomesAgentYaml() throws Exception {
        var version = new AgentVersion();
        version.setEnv("dev");
        version.setVersion("v0");
        version.setManifestHash("abc");
        var manifest = json.readTree("""
                {"kind":"Agent","spec":{"models":{"budget":{"dailyCny":300.0},"default":"qwen-plus","fallback":[]},"runtime":{"endpoint":"http://meta-agent.agents.svc:8000","language":"python"}},"metadata":{"name":"meta-agent","owner":"研发效能组 / 官德志","category":"dev","displayName":"元智能体"},"apiVersion":"keel/v1"}
                """);
        var detail = AgentDetail.of(agent(), version, null, List.of(), manifest);
        assertThat(detail.manifestYaml()).startsWith("apiVersion: keel/v1\n");
        assertThat(detail.manifestYaml()).contains("name: meta-agent");
        assertThat(detail.manifestYaml()).contains("displayName: 元智能体");
        assertThat(detail.manifestYaml()).contains("owner: 研发效能组 / 官德志");
        assertThat(detail.manifestYaml()).contains("dailyCny: 300.0");
        assertThat(detail.manifestYaml()).contains("default: qwen-plus");
        assertThat(detail.models()).containsExactly("qwen-plus");
        assertThat(detail.manifestHash()).isEqualTo("abc");
    }

    @Test void missingManifestLeavesYamlEmpty() {
        var detail = AgentDetail.of(agent(), null, null, List.of(), null);
        assertThat(detail.manifestYaml()).isNull();
        assertThat(detail.models()).isEmpty();
    }

    private static Agent agent() {
        var agent = new Agent();
        agent.setName("meta-agent");
        agent.setDisplayName("元智能体");
        agent.setLanguage("python");
        agent.setRuntime("code");
        agent.setStatus(AgentStatus.REGISTERED);
        return agent;
    }
}
