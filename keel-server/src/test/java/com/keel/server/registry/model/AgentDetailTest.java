package com.keel.server.registry.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.provisioning.ResourceNames;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentInstance;
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
        var detail = AgentDetail.of(agent(), version, null, List.of(), manifest, List.of());
        assertThat(detail.manifestYaml()).startsWith("apiVersion: keel/v1\n");
        assertThat(detail.manifestYaml()).contains("name: meta-agent");
        assertThat(detail.manifestYaml()).contains("displayName: 元智能体");
        assertThat(detail.manifestYaml()).contains("owner: 研发效能组 / 官德志");
        assertThat(detail.manifestYaml()).contains("dailyCny: 300.0");
        assertThat(detail.manifestYaml()).contains("default: qwen-plus");
        assertThat(detail.models()).containsExactly("qwen-plus");
        assertThat(detail.manifestHash()).isEqualTo("abc");
    }

    @Test void drawerUsesRealResourceIdsAndDoesNotInventProbeResults() throws Exception {
        var version = new AgentVersion();
        version.setEnv("dev");
        version.setVersion("v0");
        var manifest = json.readTree("""
                {"apiVersion":"keel/v1","kind":"Agent","metadata":{"name":"chat-demo","displayName":"对话测试","owner":"研发效能组 / amy","category":"biz"},
                 "spec":{"models":{"default":"qwen-plus","budget":{"dailyCny":30}},"eval":{"dataset":"chat-demo/smoke"},
                 "tools":[{"name":"manifest.validate","access":"read","risk":"low"},{"name":"ticket.create","access":"write","risk":"high","approval":"required"}]}}
                """);
        assertThat(ResourceNames.display("litellm_key", "chat-demo", "dev", "chat-demo", manifest)).isEqualTo("chat-demo-dev");
        assertThat(ResourceNames.display("secret", "chat-demo", "dev", "chat-demo", manifest)).isEqualTo("keel-chat-demo");
        assertThat(ResourceNames.display("dataset", "chat-demo", "dev", "chat-demo", manifest)).isEqualTo("chat-demo/smoke");
        assertThat(ResourceNames.display("oauth_client", "chat-demo", "dev", "chat-demo", manifest)).isEqualTo("chat-demo");
        assertThat(ResourceNames.display("litellm_key", "meta-agent", "dev", "meta-agent-dev", manifest)).isEqualTo("meta-agent-dev");
        var instance = new AgentInstance();
        instance.setInstanceId("chat-demo-b97db89b-pl8qs");
        instance.setSource("k8s");
        instance.setReady(true);
        var agent = agent();
        agent.setName("chat-demo");
        agent.setStatus(AgentStatus.ONLINE);
        var detail = AgentDetail.of(agent, version, "1/1", List.of(instance), manifest, List.of(
                new AgentDetail.ResourceRow("litellm_key", "chat-demo-dev", "ACTIVE"),
                new AgentDetail.ResourceRow("secret", "keel-chat-demo", "ACTIVE"),
                new AgentDetail.ResourceRow("dataset", "chat-demo/smoke", "ACTIVE")));
        assertThat(detail.instanceList()).extracting(AgentDetail.InstanceRow::instanceId).containsExactly("chat-demo-b97db89b-pl8qs");
        assertThat(detail.resources()).extracting(AgentDetail.ResourceRow::externalId)
                .containsExactly("chat-demo-dev", "keel-chat-demo", "chat-demo/smoke");
        assertThat(detail.tools()).containsExactly("manifest.validate · 读 · 低", "ticket.create · 写 · 高 · 需审批");
        assertThat(detail.selfCheck().items()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("薄网关 虚拟 Key 已发放");
            assertThat(item.passed()).isTrue();
            assertThat(item.detail()).isEqualTo("1 个模型 · ¥30/天");
        });
        assertThat(detail.selfCheck().items()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("Langfuse Key 已写入 Secret");
            assertThat(item.passed()).isTrue();
            assertThat(item.detail()).isEqualTo("keel-chat-demo");
        });
        assertThat(detail.selfCheck().items()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("/v1/health 返回 200");
            assertThat(item.passed()).isNull();
            assertThat(item.detail()).isEqualTo("未探测");
        });
        assertThat(detail.selfCheck().items()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("调用协议自检");
            assertThat(item.passed()).isNull();
        });
        assertThat(detail.selfCheck().passed()).isNull();
    }

    @Test void missingManifestLeavesYamlEmpty() {
        var detail = AgentDetail.of(agent(), null, null, List.of(), null, List.of());
        assertThat(detail.manifestYaml()).isNull();
        assertThat(detail.models()).isEmpty();
        assertThat(detail.memory()).isNull();
    }

    @Test void executionDescriptionComesFromSavedManifest() throws Exception {
        var agentManifest = json.readTree("""
                {"apiVersion":"keel/v1","spec":{"kind":"agent","models":{"budget":{"maxSteps":4}}}}
                """);
        var detail = AgentDetail.of(agent(), null, null, List.of(), agentManifest, List.of());
        assertThat(detail.memory().session()).isEqualTo("本地开发可用 · 7 天");
        assertThat(detail.memory().user()).isEqualTo("未接入");
        assertThat(detail.execution().current()).isEqualTo("声明为智能体");
        assertThat(detail.execution().target()).contains("ctx.loop");
        assertThat(detail.execution().constraints()).contains("maxSteps 4");

        var workflow = json.readTree("""
                {"apiVersion":"keel/v1","spec":{"kind":"workflow"}}
                """);
        assertThat(AgentDetail.of(agent(), null, null, List.of(), workflow, List.of()).execution().target())
                .isEqualTo("确定性代码流程");
        assertThat(AgentDetail.of(agent(), null, null, List.of(), null, List.of()).execution()).isNull();
    }

    @Test void detailShowsTwelveElementsWithoutTreatingDeclarationsAsRuntimeProof() throws Exception {
        var manifest = json.readTree("""
                {"apiVersion":"keel/v1","spec":{"kind":"agent",
                "capabilities":{"toolCalling":true,"loop":true,"memory":true},
                "tools":[{"name":"echo"}],"eval":{"dataset":"demo/seed"}}}
                """);
        var detail = AgentDetail.of(agent(), null, null, List.of(), manifest, List.of());
        assertThat(detail.elements().kind()).isEqualTo("agent");
        assertThat(detail.elements().items()).hasSize(12);
        assertThat(detail.elements().missing()).containsExactly("有版本的提示词");
        assertThat(detail.elements().items().getFirst().passed()).isNull();
        assertThat(detail.elements().items().getFirst().detail()).contains("待门禁验证");
        assertThat(detail.elements().items().get(3).passed()).isFalse();
        var workflow = json.readTree("""
                {"spec":{"kind":"workflow"}}
                """);
        assertThat(AgentDetail.of(agent(), null, null, List.of(), workflow, List.of()).elements().kind())
                .isEqualTo("workflow");
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
