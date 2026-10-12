package com.keel.server.registry.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.provisioning.EchoCredentialWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Forwards one user turn to the registered agent process and records the invoke. */
@Service
public class AgentChatService {
    private final AgentRegistryService registry;
    private final AgentEndpointClient endpoints;
    private final AuditStore audits;
    private final EchoCredentialWriter credentials;
    private final String careerMateToken;

    public AgentChatService(AgentRegistryService registry, AgentEndpointClient endpoints, AuditStore audits,
                            EchoCredentialWriter credentials,
                            @Value("${KEEL_CAREERMATE_TOKEN:}") String careerMateToken) {
        this.registry = registry;
        this.endpoints = endpoints;
        this.audits = audits;
        this.credentials = credentials;
        this.careerMateToken = careerMateToken == null ? "" : careerMateToken;
    }

    public Map<String, Object> chat(String name, String text) {
        if (text == null || text.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        }
        var detail = registry.detail(name);
        var status = detail.summary().status();
        var env = detail.summary().env();
        if (status == com.keel.server.registry.model.enums.AgentStatus.RETIRED
                || status == com.keel.server.registry.model.enums.AgentStatus.DRAFT
                || env == null || env.isBlank()
                || detail.resources().stream().anyMatch(row ->
                    ("litellm_key".equals(row.type()) || "secret".equals(row.type()))
                            && !"ACTIVE".equals(row.status()))) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, "智能体未完成开通或已下线，不能调试");
        }
        var endpoint = registry.invokeEndpoint(name);
        if (!"echo".equals(name) && endpoint.contains("//echo-agent.")) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, "请部署该智能体的独立运行服务并登记地址，避免共用回声服务的预算凭据");
        }
        if ("echo".equals(name)) {
            credentials.ensure(name);
        }
        AgentEndpointClient.Answer answer;
        try {
            var userId = callerLabel();
            answer = "careermate".equals(name)
                    ? endpoints.invoke(endpoint, text, careerMateToken, name, env, userId)
                    : endpoints.invoke(endpoint, text, null, name, env, userId);
        } catch (RuntimeException e) {
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR,
                    e.getMessage() == null ? ErrorCode.SERVER_INTERNAL_ERROR.message() : e.getMessage());
        }
        audits.append(name, env, "invoke", "low", "allowed", name, answer.traceId());
        var body = new LinkedHashMap<String, Object>();
        body.put("text", answer.text());
        body.put("traceId", answer.traceId());
        return body;
    }

    /** Display name when the console user has one. Preview sessions are not an operator. */
    private static String callerLabel() {
        var principal = ConsolePrincipal.current();
        if (principal == null || principal.readOnly() || "PREVIEW".equals(principal.mode())) {
            return null;
        }
        if (principal.displayName() != null && !principal.displayName().isBlank()) {
            return principal.displayName();
        }
        var id = principal.userId();
        return id == null || id.isBlank() ? null : id;
    }
}
