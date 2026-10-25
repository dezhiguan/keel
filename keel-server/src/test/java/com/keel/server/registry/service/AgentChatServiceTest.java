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
    private int detailReads;
    private String auditEnv;
    private String forwardedSession;
    private String forwardedUser;
    private String forwardedBridge;
    private final AgentRegistryService registry = new AgentRegistryService(null, null, null, null, null, null) {
        @Override public AgentDetail registeredDetail(String name) { detailReads++; return current; }
        @Override public String invokeEndpoint(String name) { return endpoint; }
    };
    private final AgentEndpointClient endpoints = new AgentEndpointClient() {
        @Override public java.io.InputStream openStream(String endpoint,String text,String bridge,String agent,String env,
                String user,String rag,String kbs,String session,List<Map<String,String>> history,String read,String eval,String task) {
            invocations++;
            assertThat(env).isEqualTo("test");
            return new java.io.ByteArrayInputStream("event: final\ndata: {\"answer\":\"ok\",\"trace_id\":\"trace-fixture\"}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override public Answer invoke(String endpoint, String text, String token, String agent, String env, String user) {
            invocations++;
            forwardedUser = user;
            forwardedBridge = token;
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


    @Test void preparedStreamUsesOneRequestSnapshotButNextRequestRechecksRetirement() {
        detail(AgentStatus.ONLINE, "test", List.of());
        var snapshot=service.prepareStream("qa-agent","hello","ss_fixture","owner",false);
        service.streamPrepared("qa-agent","hello","ss_fixture","owner",false,
                new java.io.ByteArrayOutputStream(),null,null,null,null,snapshot);
        assertThat(detailReads).isEqualTo(1);
        assertThat(invocations).isEqualTo(1);
        detail(AgentStatus.RETIRED,"test",List.of());
        assertThatThrownBy(() -> service.prepareStream("qa-agent","next","ss_fixture","owner",false))
                .isInstanceOfSatisfying(KeelException.class,error -> assertThat(error.code()).isEqualTo(ErrorCode.GW_AGENT_OFFLINE));
        assertThat(detailReads).isEqualTo(2);
        assertThat(invocations).isEqualTo(1);
    }

    @Test void askdbUsesAuthenticatedUsernameAndItsOwnCredential() {
        detail(AgentStatus.ONLINE, "test", List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "bridgeCredentials",
                new AgentBridgeCredentials("askdb-private", "meta-private", "career-private"));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        com.keel.server.auth.ConsolePrincipal.set(request, new com.keel.server.auth.ConsolePrincipal(
                "USER", "user-54", "guandezhi", "A shared display name", "org", "ADMIN", List.of(), false));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            service.chat("askdb", "count");
            assertThat(forwardedUser).isEqualTo("guandezhi");
            assertThat(forwardedBridge).isEqualTo("askdb-private");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test void careerMateUsesAuthenticatedSubjectInsteadOfDisplayName() {
        var user = new com.keel.server.auth.ConsolePrincipal(
                "USER", "54", "account", "Shared name", "org", "ADMIN", List.of(), false);
        assertThat(AgentChatService.callerIdentity("careermate-keel", user)).isEqualTo("54");
        assertThat(AgentChatService.callerIdentity("careermate", user)).isEqualTo("54");
        assertThat(AgentChatService.callerIdentity("careermate-keel", null)).isNull();
        assertThat(AgentChatService.callerIdentity("careermate-keel", com.keel.server.auth.ConsolePrincipal.preview())).isNull();
        assertThat(AgentChatService.callerIdentity("careermate-keel", com.keel.server.auth.ConsolePrincipal.service("service"))).isNull();
    }

    private void rejected() {
        assertThatThrownBy(() -> service.chat("qa-agent", "hello"))
                .isInstanceOfSatisfying(KeelException.class, error -> org.assertj.core.api.Assertions.assertThat(error.code())
                        .isEqualTo(ErrorCode.GW_AGENT_OFFLINE));
        assertThat(invocations).isZero();
        assertThat(auditEnv).isNull();
    }
}
