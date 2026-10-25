package com.keel.server.registry.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.ApiError;
import com.keel.server.common.KeelException;
import com.keel.server.common.PageResult;
import com.keel.server.common.R;
import com.keel.server.evaluation.EvalJobs;
import com.keel.server.insight.AgentUsageService;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.registry.service.AgentChatService;
import com.keel.server.registry.service.AgentRegistryService;
import com.keel.server.registry.service.LifecycleService;
import com.keel.server.registry.service.SelfCheckService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.keel.server.auth.UserReadContext readContexts;
    @org.springframework.beans.factory.annotation.Autowired
    private com.keel.server.tasks.TaskContext taskContexts;
    @org.springframework.beans.factory.annotation.Autowired
    private com.keel.server.tasks.AnalysisTasks analysisTasks;
    @org.springframework.beans.factory.annotation.Autowired
    private com.keel.server.registry.service.DebugChatRequests chatRequests;
    private final AgentRegistryService agentRegistryService;
    private final LifecycleService lifecycleService;
    private final AgentChatService chats;
    private final AgentUsageService usage;
    private final EvalJobs evaluations;
    private final com.keel.server.registry.service.DebugSessionService sessions;
    private final ObjectMapper json = new ObjectMapper();

    public AgentController(AgentRegistryService agentRegistryService, LifecycleService lifecycleService,
                           AgentChatService chats, AgentUsageService usage, EvalJobs evaluations,
                           com.keel.server.registry.service.DebugSessionService sessions) {
        this.agentRegistryService = agentRegistryService;
        this.lifecycleService = lifecycleService;
        this.chats = chats;
        this.usage = usage;
        this.sessions = sessions;
        this.evaluations = evaluations;
    }

    @GetMapping
    public R<PageResult<AgentSummary>> list(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|biz|dev") String category,
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean usage,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Pattern(regexp = "10|20|50|100") String size) {
        var pageResult = agentRegistryService.page(env, category, status, q, page, Integer.parseInt(size));
        var items = usage ? this.usage.apply(pageResult.items(), env) : pageResult.items();
        return R.ok(new PageResult<>(pageResult.page(), pageResult.size(), pageResult.total(), items));
    }

    @PostMapping
    public R<SelfCheckService.Report> register(@RequestBody JsonNode body) {
        return R.ok(lifecycleService.register(body));
    }

    @GetMapping("/name-check")
    public R<java.util.Map<String, Object>> nameCheck(@RequestParam String name) {
        return R.ok(agentRegistryService.nameCheck(name));
    }

    @PostMapping("/manifest-preview")
    public R<java.util.Map<String, Object>> preview(@RequestBody JsonNode form) {
        var rendered = agentRegistryService.preview(form);
        return R.ok(java.util.Map.of("yaml", rendered.yaml(), "warnings", rendered.warnings()));
    }

    @GetMapping("/{name}")
    public R<AgentDetail> detail(@PathVariable String name,
            @RequestParam(defaultValue = "true") boolean includeRuntimeEvidence) {
        var detail = includeRuntimeEvidence
                ? agentRegistryService.detail(name) : agentRegistryService.registeredDetail(name);
        var env = detail.summary() == null ? null : detail.summary().env();
        var enriched = usage.apply(detail.summary(), env == null ? "all" : env);
        return R.ok(evaluations.withGate(detail.withGatewayBudget(enriched.dailyBudgetCny(), enriched.costCny())));
    }

    @GetMapping("/{name}/elements")
    public R<com.keel.server.registry.service.ElementEvidence.Report> elements(@PathVariable String name) {
        return R.ok(agentRegistryService.elements(name));
    }

    @PostMapping("/{name}/chat")
    public R<java.util.Map<String, Object>> chat(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(chats.chat(name, body.path("text").asText(""),
                body.hasNonNull("sessionId") ? body.path("sessionId").asText() : null));
    }

    @GetMapping("/{name}/sessions")
    public R<java.util.Map<String, Object>> sessions(@PathVariable String name, @RequestParam(required = false) String cursor) {
        var owner = sessions.requireOwner();
        var env = agentRegistryService.registeredDetail(name).summary().env();
        return R.ok(sessions.list(name, env, owner, cursor));
    }

    @PostMapping("/{name}/sessions")
    public R<java.util.Map<String, Object>> createSession(@PathVariable String name) {
        var owner = sessions.requireOwner();
        var env = agentRegistryService.registeredDetail(name).summary().env();
        return R.ok(sessions.create(name, env, owner));
    }

    @DeleteMapping("/{name}/sessions/{id}")
    public R<java.util.Map<String, Object>> deleteSession(@PathVariable String name, @PathVariable String id) {
        sessions.delete(name, sessions.requireOwner(), id);
        return R.ok(java.util.Map.of("id", id));
    }

    @GetMapping("/{name}/sessions/{id}/messages")
    public R<java.util.Map<String, Object>> sessionMessages(@PathVariable String name, @PathVariable String id,
                                                            @RequestParam(required = false) Long before) {
        return R.ok(sessions.messages(name, sessions.requireOwner(), id, before));
    }

    @PostMapping(value = "/{name}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody streamChat(@PathVariable String name, @RequestBody JsonNode body,
                                            HttpServletRequest request, HttpServletResponse response) {
        var text = body.path("text").asText("");
        var sessionId = body.path("sessionId").asText("");
        var owner = sessions.requireOwner();
        var evaluationRun=request.getHeader("X-Keel-Eval-Run");
        if(evaluationRun!=null && !evaluationRun.matches("[A-Za-z0-9_-]{1,128}"))
            throw new KeelException(com.keel.common.error.ErrorCode.SERVER_INVALID_PARAM,"评测运行标记无效");
        var taskAcceptance=request.getHeader("X-Keel-Analysis-Acceptance");
        if(taskAcceptance!=null && !taskAcceptance.matches("[A-Za-z0-9_-]{1,64}"))throw com.keel.server.tasks.TaskContext.invalid("专项验收标记无效");
        var remember = evaluationRun == null && taskAcceptance == null;
        var forwardedEvaluation=taskAcceptance==null?evaluationRun:"task-"+taskAcceptance;
        com.keel.server.registry.model.dto.AgentDetail detail;
        try {
            detail=chats.prepareStream(name, text, sessionId, owner, remember);
        } catch (KeelException error) {
            return jsonError(response,error);
        }
        // Capture trusted identity on the authenticated servlet thread, before async streaming.
        var readContext=readContexts.issue(com.keel.server.auth.ConsolePrincipal.current(),name,detail.summary().env(),sessionId);
        var taskContext=taskContexts.issueUser(com.keel.server.auth.ConsolePrincipal.current(),name,detail.summary().env(),sessionId,text,
            body.path("requestId").asText(""),evaluationRun!=null && taskAcceptance==null,taskAcceptance==null?"user":"acceptance:"+taskAcceptance);
        if(taskContext!=null && analysisTasks.hasReceipt(taskContext,text))taskContext=taskContexts.forRequestReplay(taskContext);
        var forwardedTaskContext=taskContext;
        var streamPrincipal=com.keel.server.auth.ConsolePrincipal.current();
        var relayTrace=com.keel.server.common.TraceIds.current();
        if(relayTrace==null || relayTrace.isBlank())relayTrace=java.util.UUID.randomUUID().toString().replace("-","");
        var errorTrace=relayTrace;
        var relayRun="console-relay-"+java.util.UUID.randomUUID();
        com.keel.server.registry.service.DebugChatRequests.Claim claim;
        try {
            claim=remember ? chatRequests.begin(name,detail.summary().env(),owner,sessionId,
                    body.path("requestId").asText(""),text) : null;
        } catch (KeelException error) { return jsonError(response,error); }
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        if (claim!=null && claim.replay()!=null) return output -> {
            output.write(claim.replay().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
        };
        return output -> com.keel.server.registry.service.DebugChatExecution.run(() -> {
            try (var guarded=new com.keel.server.registry.service.DebugStreamOutput(output)) {
                try {
                    chats.streamPrepared(name, text, sessionId, owner, remember, guarded, readContext, forwardedEvaluation, forwardedTaskContext, streamPrincipal, detail);
                } catch (RuntimeException error) {
                    org.slf4j.LoggerFactory.getLogger(AgentController.class).warn(
                            "trace_id={} agent={} stream relay interrupted run_id={} error_type={} cause_type={}",errorTrace,name,relayRun,
                            error.getClass().getSimpleName(),error.getCause()==null?"none":error.getCause().getClass().getSimpleName());
                    guarded.write(streamError(errorTrace,relayRun).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    guarded.flush();
                } finally { chatRequests.finish(claim,guarded.terminal()); }
            }
        });
    }

    private StreamingResponseBody jsonError(HttpServletResponse response,KeelException error) {
        // The stream route must explicitly negotiate JSON for preflight rejection.
        response.setStatus(error.code().http());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        final byte[] payload;
        try { payload=json.writeValueAsBytes(ApiError.of(error.code(),error.getMessage())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failed) { throw new IllegalStateException(failed); }
        return output -> { output.write(payload); output.flush(); };
    }

    /** These IDs identify the real console relay execution, never an invented agent run. */
    static String streamError(String traceId,String relayRun) throws java.io.IOException {
        var code=com.keel.common.error.ErrorCode.SERVER_INTERNAL_ERROR;
        return "event: error\ndata: "+new ObjectMapper().writeValueAsString(java.util.Map.of(
                "code",code.name(),"message","流式连接中断或处理失败，结果未完成，请重试",
                "trace_id",traceId,"run_id",relayRun,"retryable",code.retryable()))+"\n\n";
    }

    @DeleteMapping("/{name}")
    public R<java.util.Map<String, Object>> delete(@PathVariable String name) {
        lifecycleService.delete(name);
        return R.ok(java.util.Map.of("name", name));
    }

    @PostMapping("/{name}/retire")
    public R<java.util.Map<String, Object>> retire(@PathVariable String name, @RequestBody JsonNode body) {
        lifecycleService.retire(name, body.path("env").asText(""));
        return R.ok(java.util.Map.of("name", name, "status", "RETIRED"));
    }
}
