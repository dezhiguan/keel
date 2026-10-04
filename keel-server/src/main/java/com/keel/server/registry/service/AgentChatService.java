package com.keel.server.registry.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.provisioning.EchoCredentialWriter;
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

    public AgentChatService(AgentRegistryService registry, AgentEndpointClient endpoints, AuditStore audits,
                            EchoCredentialWriter credentials) {
        this.registry = registry;
        this.endpoints = endpoints;
        this.audits = audits;
        this.credentials = credentials;
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
            answer = endpoints.invoke(endpoint, text);
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
}
