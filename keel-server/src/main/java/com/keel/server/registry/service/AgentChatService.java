package com.keel.server.registry.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.provisioning.EchoCredentialWriter;
import com.keel.server.provisioning.SecretWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.keel.server.insight.SavedTraces;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.io.OutputStream;

/** Forwards one user turn to the registered agent process and records the invoke. */
@Service
public class AgentChatService {
    private final AgentRegistryService registry;
    private final AgentEndpointClient endpoints;
    private final AuditStore audits;
    private final EchoCredentialWriter credentials;
    private final String careerMateToken;
    @Value("${KEEL_META_AGENT_TOKEN:}")
    private String metaAgentToken;
    private final SavedTraces saved;
    private final SecretWriter secrets;
    private final DebugSessionService sessions;

    public AgentChatService(AgentRegistryService registry, AgentEndpointClient endpoints, AuditStore audits,
                            EchoCredentialWriter credentials,
                            String careerMateToken) {
        this(registry, endpoints, audits, credentials, careerMateToken, SavedTraces.EMPTY, null, null);
    }

    @Autowired
    public AgentChatService(AgentRegistryService registry, AgentEndpointClient endpoints, AuditStore audits,
                            EchoCredentialWriter credentials,
                            @Value("${KEEL_CAREERMATE_TOKEN:}") String careerMateToken, SavedTraces saved,
                            SecretWriter secrets, DebugSessionService sessions) {
        this.registry = registry;
        this.endpoints = endpoints;
        this.audits = audits;
        this.credentials = credentials;
        this.careerMateToken = careerMateToken == null ? "" : careerMateToken;
        this.saved = saved;
        this.secrets = secrets;
        this.sessions = sessions;
    }

    public Map<String, Object> chat(String name, String text) {
        return chat(name, text, null);
    }

    public Map<String, Object> chat(String name, String text, String sessionId) {
        if (text == null || text.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        }
        if (sessionId != null && !sessionId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "sessionId 格式不正确");
        }
        var detail = registry.registeredDetail(name);
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
        long started = System.nanoTime();
        try {
            var userId = callerLabel();
            var rag = secrets == null ? null : secrets.knowledgeCredential(name);
            var bridge = bridgeToken(name);
            if (sessionId != null) {
                answer = endpoints.invoke(endpoint, text, bridge, name, env, userId,
                        rag == null ? null : rag.token(), rag == null ? null : rag.kbIds(), scopedSessionId(sessionId));
            } else {
                answer = rag == null || rag.token().isBlank()
                        ? endpoints.invoke(endpoint, text, bridge, name, env, userId)
                        : endpoints.invoke(endpoint, text, bridge, name, env, userId, rag.token(), rag.kbIds());
            }
        } catch (RuntimeException e) {
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR,
                    e.getMessage() == null ? ErrorCode.SERVER_INTERNAL_ERROR.message() : e.getMessage());
        }
        audits.append(name, env, "invoke", "low", "allowed", name, answer.traceId());
        if (answer.traceId() != null && !answer.traceId().isBlank()) {
            saved.save(name, env, answer.traceId(), "", (int) ((System.nanoTime() - started) / 1_000_000));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("text", answer.text());
        body.put("traceId", answer.traceId());
        return body;
    }

    /** Reject a debug stream before the response is committed. */
    public AgentDetail prepareStream(String name, String text, String sessionId, String owner, boolean remember) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "sessionId 格式不正确");
        }
        var detail = ready(name, text);
        if (remember) sessions.require(name, detail.summary().env(), owner, sessionId);
        return detail;
    }

    /** Copy the agent SSE stream. A finished final is stored for this console user. */
    public void streamChat(String name, String text, String sessionId, String owner, boolean remember, OutputStream output) {
        streamChat(name,text,sessionId,owner,remember,output,null);
    }

    public void streamChat(String name, String text, String sessionId, String owner, boolean remember, OutputStream output, String readContext) {
        streamChat(name,text,sessionId,owner,remember,output,readContext,null);
    }

    public void streamChat(String name, String text, String sessionId, String owner, boolean remember, OutputStream output, String readContext, String evaluationRun) {
        streamChat(name,text,sessionId,owner,remember,output,readContext,evaluationRun,null);
    }

    public void streamChat(String name,String text,String sessionId,String owner,boolean remember,OutputStream output,String readContext,String evaluationRun,String taskContext) {
        streamChat(name,text,sessionId,owner,remember,output,readContext,evaluationRun,taskContext,ConsolePrincipal.current());
    }

    public void streamChat(String name,String text,String sessionId,String owner,boolean remember,OutputStream output,String readContext,String evaluationRun,String taskContext,ConsolePrincipal principal) {
        var detail = prepareStream(name,text,sessionId,owner,remember);
        streamPrepared(name,text,sessionId,owner,remember,output,readContext,evaluationRun,taskContext,principal,detail);
    }

    public void streamPrepared(String name,String text,String sessionId,String owner,boolean remember,OutputStream output,String readContext,String evaluationRun,String taskContext,ConsolePrincipal principal,AgentDetail detail) {
        var env = detail.summary().env();
        if ("echo".equals(name)) credentials.ensure(name);
        var endpoint = registry.invokeEndpoint(name);
        var userId = callerLabel(principal);
        var rag = secrets == null ? null : secrets.knowledgeCredential(name);
        var bridge = bridgeToken(name);
        var history = remember ? sessions.history(name, owner, sessionId) : List.<Map<String, String>>of();
        // Other runtimes may not yet consume structured history. Preserve their
        // existing transcript behavior until their SDK advertises support.
        var structured = "python".equalsIgnoreCase(detail.summary().language());
        var inputText = text;
        if (!structured && !history.isEmpty()) {
            var prior = new StringBuilder("以下是本会话最近的对话，请接着回答本轮问题，不要复述这段提示。\n");
            for (var message : history) prior.append("assistant".equals(message.get("role")) ? "助手：" : "用户：")
                    .append(message.get("content")).append('\n');
            inputText = prior.append("本轮：").append(text).toString();
        }
        long started = System.nanoTime();
        try (var input = endpoints.openStream(endpoint, inputText, bridge, name, env, userId,
                rag == null ? null : rag.token(), rag == null ? null : rag.kbIds(),
                scopedSessionId(owner, sessionId), structured ? history : null, readContext, evaluationRun, taskContext)) {
            var fin = DebugSseRelay.copy(input, output);
            var traceId = fin == null ? null : fin.traceId();
            audits.append(name, env, "invoke", "low", fin == null ? "error" : "allowed", name, traceId);
            if (fin != null && fin.rememberable() && remember) {
                sessions.appendTurn(name, owner, sessionId, text, fin.answer(), traceId);
            }
            if (traceId != null && !traceId.isBlank()) {
                saved.save(name, env, traceId, "", (int) ((System.nanoTime() - started) / 1_000_000));
            }
        } catch (java.io.IOException error) {
            audits.append(name, env, "invoke", "low", "error", name, null);
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, "调试连接已断开");
        } catch (RuntimeException error) {
            audits.append(name, env, "invoke", "low", "error", name, null);
            if (error instanceof KeelException keel) throw keel;
            throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR,
                    error.getMessage() == null ? ErrorCode.SERVER_INTERNAL_ERROR.message() : error.getMessage());
        }
    }

    private String bridgeToken(String name) {
        return switch (name) {
            case "meta-agent" -> metaAgentToken;
            case "careermate", "careermate-keel" -> careerMateToken;
            default -> null;
        };
    }

    private AgentDetail ready(String name, String text) {
        if (text == null || text.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        }
        var detail = registry.registeredDetail(name);
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
        return detail;
    }

    public void channelStream(String name, String text, String channel, String userId, OutputStream output) {
        if (text == null || text.isBlank()) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "输入不能为空");
        var detail = registry.registeredDetail(name);
        if (detail.summary().status() != com.keel.server.registry.model.enums.AgentStatus.ONLINE) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, "智能体未上线");
        }
        var endpoint = registry.invokeEndpoint(name);
        if (!"echo".equals(name) && endpoint.contains("//echo-agent.")) {
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, "智能体没有独立运行服务");
        }
        var bridge = bridgeToken(name);
        var rag = secrets == null ? null : secrets.knowledgeCredential(name);
        try {
            endpoints.stream(endpoint, text, bridge, name, detail.summary().env(), channel, userId,
                    rag == null ? null : rag.token(), rag == null ? null : rag.kbIds(), output);
            audits.append(name, detail.summary().env(), "invoke", "low", "allowed", name, null);
        } catch (RuntimeException error) {
            audits.append(name, detail.summary().env(), "invoke", "low", "error", name, null);
            throw error;
        }
    }

    /** Display name when the console user has one. Preview sessions are not an operator. */
    private static String callerLabel() { return callerLabel(ConsolePrincipal.current()); }

    private static String callerLabel(ConsolePrincipal principal) {
        if (principal == null || principal.readOnly() || "PREVIEW".equals(principal.mode())) {
            return null;
        }
        if (principal.displayName() != null && !principal.displayName().isBlank()) {
            return principal.displayName();
        }
        var id = principal.userId();
        return id == null || id.isBlank() ? null : id;
    }

    /** A browser supplied ID never selects another console user's local agent history. */
    private static String scopedSessionId(String id) {
        var principal = ConsolePrincipal.current();
        var owner = principal == null || principal.userId() == null ? "anonymous" : principal.userId();
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((owner + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String scopedSessionId(String owner, String id) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((owner + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
