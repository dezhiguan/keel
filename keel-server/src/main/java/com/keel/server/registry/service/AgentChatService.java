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
        var endpoint = registry.invokeEndpoint(name);
        if ("echo".equals(name)) {
            credentials.ensure(name);
        }
        AgentEndpointClient.Answer answer;
        try {
            var userId = callerLabel();
            answer = "careermate".equals(name)
                    ? endpoints.invoke(endpoint, text, careerMateToken, name, "dev", userId)
                    : endpoints.invoke(endpoint, text, null, name, "dev", userId);
        } catch (RuntimeException e) {
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR,
                    e.getMessage() == null ? ErrorCode.SERVER_INTERNAL_ERROR.message() : e.getMessage());
        }
        audits.append(name, "dev", "invoke", "low", "allowed", name, answer.traceId());
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
