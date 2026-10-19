package com.keel.server.registry.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.provisioning.EchoCredentialWriter;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.enums.AgentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentVersion;
import java.util.Map;

class AgentChatServiceTest {
    private AgentDetail current;
    private String endpoint = "http://qa-agent.agents.svc:8000";
    private int invocations;
    private String auditEnv;
    private String forwardedSession;
    private final AgentRegistryService registry = new AgentRegistryService(null, null, null, null, null, null) {
        @Override public AgentDetail registeredDetail(String name) { return current; }
        @Override public String invokeEndpoint(String name) { return endpoint; }
    };
    private final AgentEndpointClient endpoints = new AgentEndpointClient() {
        @Override public Answer invoke(String endpoint, String text, String token, String agent, String env, String user) {
            invocations++;
            assertThat(env).isEqualTo("test");
            return new Answer("ok", "trace-qa");
        }
        @Override public Answer invoke(String endpoint, String text, String token, String agent, String env,
                                       String user, String ragToken, String ragKbIds, String sessionId) {
            forwardedSession = sessionId;
            return new Answer("ok", "trace-qa");
        }
    };
    private final AuditStore audits = new AuditStore() {
        public void append(String agent, String env, String action, String risk, String decision, String resource, String trace) {
            auditEnv = env;
        }
        public Map<String, Object> page(String agent, String risk, String env, int page, int size) { return Map.of(); }
        public Map<String, Object> verify(String agent) { return Map.of(); }
    };
    private final AgentChatService service = new AgentChatService(registry, endpoints, audits, null, "");

    private void detail(AgentStatus status, String env, List<AgentDetail.ResourceRow> resources) {
        var agent = new Agent();
        agent.setName("qa-agent");
        agent.setStatus(status);
        var version = new AgentVersion();
        version.setEnv(env);
        current = AgentDetail.of(agent, version, null, List.of(), null, resources);
    }

    @Test void retiredAgentCannotInvokeEvenWithActiveResources() {
        detail(AgentStatus.RETIRED, "dev", List.of(new AgentDetail.ResourceRow("litellm_key", "qa-agent-dev", "ACTIVE")));
        rejected();
    }

    @Test void rolledBackAgentCannotBorrowEchoCredentials() {
        detail(AgentStatus.REGISTERED, "dev", List.of(new AgentDetail.ResourceRow("litellm_key", "qa-agent-dev", "REVOKED")));
        rejected();
    }

    @Test void sharedEchoEndpointCannotBillAnotherAgent() {
        detail(AgentStatus.REGISTERED, "dev", List.of());
        endpoint = "http://echo-agent.keel-system.svc.cluster.local:8000";
        rejected();
    }

    @Test void invokeAndAuditUseRegisteredEnvironment() {
        detail(AgentStatus.ONLINE, "test", List.of());
        service.chat("qa-agent", "hello");
        assertThat(auditEnv).isEqualTo("test");
        assertThat(invocations).isEqualTo(1);
    }

    @Test void chatSessionIsValidatedAndScopedBeforeInvoke() {
        detail(AgentStatus.ONLINE, "test", List.of());
        service.chat("qa-agent", "first", "browser-session");
        assertThat(forwardedSession).matches("[0-9a-f]{64}");
        var same = forwardedSession;
        service.chat("qa-agent", "second", "browser-session");
        assertThat(forwardedSession).isEqualTo(same);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.chat("qa-agent", "bad", "../other"))
                .isInstanceOf(KeelException.class);
    }

    private void rejected() {
        assertThatThrownBy(() -> service.chat("qa-agent", "hello"))
                .isInstanceOfSatisfying(KeelException.class, error -> org.assertj.core.api.Assertions.assertThat(error.code())
                        .isEqualTo(ErrorCode.GW_AGENT_OFFLINE));
        assertThat(invocations).isZero();
        assertThat(auditEnv).isNull();
    }
}
