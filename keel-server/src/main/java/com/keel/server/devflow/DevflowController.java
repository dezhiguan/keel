package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/devflow")
public class DevflowController {
    private final DevflowService devflow;
    private final SandboxService sandbox;
    private final HoldoutService holdout;
    private final MetaAgentExecution execution;

    public DevflowController(DevflowService devflow, SandboxService sandbox, HoldoutService holdout, MetaAgentExecution execution) {
        this.devflow = devflow;
        this.sandbox = sandbox;
        this.holdout = holdout;
        this.execution = execution;
    }

    @GetMapping("/jobs")
    public R<DevflowTypes.JobList> jobs(@RequestParam(required = false) String layer,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String stage,
                                        @RequestParam(required = false) String batch,
                                        @RequestParam(defaultValue = "false") boolean mine) {
        return R.ok(devflow.list(layer, status, stage, batch, mine, actor()));
    }

    @PostMapping("/jobs")
    public R<DevflowTypes.Job> createJob(@RequestBody DevflowTypes.Draft body) {
        requireWrite();
        if (Boolean.TRUE.equals(body.execution())) return R.ok(execution.create(body, actor()));
        if ("DEV".equals(body.layer())) {
            MetaAgentExecution.requireAdmin();
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "研发智能体新建必须由 meta-agent 执行原型生产流程");
        }
        return R.ok(devflow.submit(body, actor(), serviceAgent()));
    }

    @GetMapping("/jobs/{jobId}")
    public R<DevflowTypes.Job> job(@PathVariable String jobId) {
        return R.ok(devflow.job(jobId));
    }

    @PostMapping("/jobs/{jobId}/takeover")
    public R<DevflowTypes.Job> takeover(@PathVariable String jobId) {
        requireWrite();
        if (execution.get(jobId) != null) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "骨架任务请在已交付的 PR 中继续开发");
        return R.ok(devflow.takeover(jobId, actor()));
    }

    @PostMapping("/jobs/{jobId}/handback")
    public R<DevflowTypes.Job> handback(@PathVariable String jobId) {
        requireWrite();
        if (execution.get(jobId) != null) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "骨架任务请在已交付的 PR 中继续开发");
        return R.ok(devflow.handback(jobId, actor()));
    }

    @PostMapping("/jobs/{jobId}/assist")
    public R<DevflowTypes.Job> assist(@PathVariable String jobId, @RequestBody DevflowTypes.Assist body) {
        requireWrite();
        if (execution.get(jobId) != null) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "骨架任务请在已交付的 PR 中继续开发");
        return R.ok(devflow.assist(jobId, body == null ? null : body.instruction(), actor()));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public R<DevflowTypes.Job> cancel(@PathVariable String jobId) {
        requireWrite();
        return R.ok(execution.get(jobId) != null ? execution.cancel(jobId, actor()) : devflow.cancel(jobId, actor()));
    }

    @PostMapping("/jobs/{jobId}/stages/{stage}/report")
    public R<Map<String, Object>> report(@PathVariable String jobId, @PathVariable String stage,
                                         @RequestBody DevflowTypes.StageReport body) {
        var principal = requireService();
        var job = devflow.report(jobId, stage, body, principal.username());
        return R.ok(Map.of("jobId", job.jobId(), "stage", job.stage(), "attempt", job.fixRounds() + 1, "status", body.status()));
    }

    @GetMapping("/jobs/{jobId}/review")
    public R<Map<String, Object>> review(@PathVariable String jobId) {
        return R.ok(execution.review(jobId));
    }

    @GetMapping("/jobs/{jobId}/execution")
    public R<com.fasterxml.jackson.databind.node.ObjectNode> execution(@PathVariable String jobId) {
        return R.ok(execution.get(jobId));
    }

    @PostMapping("/jobs/{jobId}/requirement")
    public R<com.fasterxml.jackson.databind.node.ObjectNode> decideRequirement(@PathVariable String jobId,
            @RequestBody DevflowTypes.RequirementDecision body) {
        return R.ok(execution.decide(jobId, body));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public R<com.fasterxml.jackson.databind.node.ObjectNode> retry(@PathVariable String jobId) {
        return R.ok(execution.retry(jobId));
    }

    @PostMapping("/jobs/{jobId}/seed-cases")
    public R<DevflowTypes.SeedResult> seed(@PathVariable String jobId, @RequestBody DevflowTypes.SeedAccept body) {
        requireWrite();
        if (execution.get(jobId) != null) return R.ok(execution.acceptEval(jobId, body, actor()));
        var ids = body == null ? List.<String>of() : body.acceptedCaseIds();
        var cases = body == null || body.cases() == null ? List.<DevflowTypes.SeedCase>of() : body.cases();
        return R.ok(devflow.acceptSeeds(jobId, ids, cases, actor()));
    }

    @PostMapping("/jobs/{jobId}/human-seeds")
    public R<com.fasterxml.jackson.databind.node.ObjectNode> humanSeeds(@PathVariable String jobId,
            @RequestBody HumanSeeds body) {
        requireWrite();
        return R.ok(execution.uploadHumanSeeds(jobId, body == null ? null : body.cases()));
    }

    @PostMapping("/jobs/{jobId}/tool-grants")
    public R<com.fasterxml.jackson.databind.node.ObjectNode> grantTool(@PathVariable String jobId,
            @RequestBody ToolGrant body) {
        requireWrite();
        return R.ok(execution.grantTool(jobId, body == null ? null : body.toolName(),
                body == null ? null : body.versionRange()));
    }

    @GetMapping("/holdout/{jobId}")
    public R<Map<String, Object>> holdout(@PathVariable String jobId) {
        var principal = requireService();
        return R.ok(holdout.read(jobId, principal.username()));
    }

    @PostMapping("/holdout-results")
    public R<Map<String, Object>> holdoutResult(@RequestBody HoldoutSubmission body) {
        var principal = requireService();
        var scores = body == null || body.byTag() == null ? List.<HoldoutRules.TagScore>of() : body.byTag();
        holdout.submit(body == null ? null : body.jobId(), scores, principal.username());
        return R.ok(Map.of());
    }

    @GetMapping("/batches")
    public R<DevflowTypes.BatchList> batches() {
        return R.ok(devflow.batches());
    }

    @PostMapping("/batches/preview")
    public R<DevflowTypes.Preview> preview() {
        return R.ok(new DevflowTypes.Preview(devflow.preview()));
    }

    @PostMapping("/batches")
    public R<DevflowTypes.Batch> create(@RequestBody DevflowTypes.BatchCreate body) {
        requireWrite();
        return R.ok(devflow.create(body == null ? null : body.title(), body == null ? null : body.rows(), actor()));
    }

    @PostMapping("/sandbox/runs")
    public R<DevflowTypes.SandboxRun> startSandbox(@RequestBody DevflowTypes.SandboxRequest body) {
        var principal = requireService();
        var request = body == null ? new DevflowTypes.SandboxRequest(null, null, null) : body;
        return R.ok(sandbox.start(request.jobId(), request.repo(), request.ref(), principal.username(), Instant.now()));
    }

    @GetMapping("/sandbox/runs/{runId}")
    public R<DevflowTypes.SandboxRun> sandboxRun(@PathVariable String runId) {
        requireService();
        return R.ok(sandbox.get(runId, Instant.now()));
    }

    @GetMapping("/settings")
    public R<DevflowTypes.Settings> settings() {
        return R.ok(devflow.settings());
    }

    @PutMapping("/settings")
    public R<DevflowTypes.Settings> save(@RequestBody DevflowTypes.Settings body) {
        requireWrite();
        var principal = ConsolePrincipal.current();
        if (principal != null && !"ADMIN".equals(principal.platformRole())) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, "规则仅平台管理员可改");
        }
        return R.ok(devflow.save(body, actor()));
    }

    private static void requireWrite() {
        var principal = ConsolePrincipal.current();
        if (principal != null && principal.readOnly()) {
            throw new KeelException(ErrorCode.AUTH_PREVIEW_READONLY, ErrorCode.AUTH_PREVIEW_READONLY.message());
        }
    }

    private static String actor() {
        var principal = ConsolePrincipal.current();
        if (principal == null || principal.displayName() == null || principal.displayName().isBlank()) return "平台管理员";
        return principal.displayName();
    }

    private static String serviceAgent() {
        var principal = ConsolePrincipal.current();
        if (principal == null || !"SERVICE".equals(principal.mode())) {
            return null;
        }
        return principal.username();
    }

    public record HoldoutSubmission(String jobId, List<HoldoutRules.TagScore> byTag) {}

    public record HumanSeeds(List<DevflowTypes.SeedCase> cases) {}

    public record ToolGrant(String toolName, String versionRange) {}

    private static ConsolePrincipal requireService() {
        var principal = ConsolePrincipal.current();
        if (principal == null || !"SERVICE".equals(principal.mode())) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
        return principal;
    }
}
