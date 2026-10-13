package com.keel.server.devflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.registry.service.WizardCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.time.Instant;
import java.util.UUID;

/** Durable minimal workflow. No user ticket or in-memory SDK resume token is kept between H1 turns. */
@Service
public class MetaAgentExecution {
    private static final Map<String, String> DEV_ROLES = Map.of(
            "dev-lead", "supervisor", "spec-agent", "graph-agent", "dev-agent", "graph-agent",
            "eval-agent", "tool-agent", "code-review", "tool-agent", "ci-doctor", "tool-agent",
            "release-agent", "tool-agent");
    private static final Map<String, Set<String>> DEV_TOOLS = Map.of(
            "dev-lead", Set.of("devflow.job.read", "devflow.stage.report"),
            "spec-agent", Set.of("devflow.catalog.search"),
            "dev-agent", Set.of("sandbox.run", "git.repo.create", "git.pr.open"),
            "eval-agent", Set.of("sandbox.run"),
            "code-review", Set.of("git.pr.diff", "git.pr.comment"),
            "ci-doctor", Set.of("ci.log.fetch", "keel.gate.report.read"),
            "release-agent", Set.of("ci.workflow.dispatch", "git.pr.merge"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final DevflowService jobs;
    private final WizardCatalog catalog;
    private final AgentEndpointClient agent;
    private final SandboxService sandbox;
    private final String endpoint;
    private final String token;

    @Autowired
    public MetaAgentExecution(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
                              DevflowService jobs, WizardCatalog catalog, AgentEndpointClient agent,
                              SandboxService sandbox,
                              @Value("${KEEL_META_AGENT_URL:}") String endpoint,
                              @Value("${KEEL_META_AGENT_TOKEN:}") String token) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(transactions);
        this.jobs = jobs; this.catalog = catalog; this.agent = agent; this.sandbox = sandbox;
        this.endpoint = endpoint; this.token = token;
    }

    MetaAgentExecution(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
                       DevflowService jobs, WizardCatalog catalog, AgentEndpointClient agent,
                       String endpoint, String token) {
        this(jdbc, json, transactions, jobs, catalog, agent, null, endpoint, token);
    }

    public static void requireAdmin() {
        var user = ConsolePrincipal.current();
        if (user == null || user.readOnly() || !"ADMIN".equals(user.platformRole()) || "SERVICE".equals(user.mode())) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, "元智能体生产仅平台管理员可操作");
        }
    }

    public DevflowTypes.Job create(DevflowTypes.Draft draft, String actor) {
        requireAdmin();
        validate(draft);
        if (endpoint.isBlank() || token.isBlank()) throw invalid("元智能体服务尚未配置");
        var tools = catalog.sharedTools();
        for (String name : draft.tools() == null ? List.<String>of() : draft.tools()) {
            if (tools.stream().noneMatch(t -> name.equals(t.get("name")))) throw invalid("工具不在当前目录：" + name);
        }
        return tx.execute(status -> {
            // Serializes name allocation across both server replicas, including an existing registered agent.
            jdbc.execute("SELECT pg_advisory_xact_lock(481516)");
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent WHERE name = ?)", Boolean.class, draft.targetAgent()))) {
                throw invalid("智能体 ID 已登记，请换一个名称");
            }
            var job = jobs.submit(draft, actor);
            var selectedTools = tools.stream().filter(t -> draft.tools() != null && draft.tools().contains(t.get("name")))
                    .map(t -> new DevflowTypes.ToolRef(String.valueOf(t.get("name")), String.valueOf(t.get("risk")), "待工具所有者授权")).toList();
            jdbc.update("UPDATE devflow_job SET tools_json=?::jsonb WHERE job_id=?", json.valueToTree(selectedTools).toString(), job.jobId());
            var state = json.createObjectNode();
            state.put("jobId", job.jobId()).put("phase", "QUEUED").put("version", 1).put("revisions", 0);
            var requirement = state.putObject("requirement");
            requirement.put("layer", "dev").put("kind", "CREATE").put("target_agent", job.targetAgent())
                    .put("title", job.title()).put("goal", draft.goal()).put("users", draft.users())
                    .put("io", draft.io()).put("success", draft.success()).put("template", draft.template())
                    .put("dailyBudgetCny", draft.dailyBudgetCny()).put("owner", job.ownerOrg() + " / " + actor);
            requirement.set("tools", json.valueToTree(draft.tools() == null ? List.of() : draft.tools()));
            state.set("catalog", json.valueToTree(tools));
            jdbc.update("INSERT INTO devflow_execution(job_id,state_json,action) VALUES (?,?::jsonb,'DRAFT')", job.jobId(), state.toString());
            return jobs.progress(job.jobId(), "QUEUED", "SPEC", "已排队，等待元智能体生成需求草稿");
        });
    }

    public static void validate(DevflowTypes.Draft draft) {
        if (!"DEV".equals(draft.layer()) || !"CREATE".equals(draft.kind()) || !"COLLAB".equals(draft.mode())
                || !DEV_ROLES.getOrDefault(draft.targetAgent(), "").equals(draft.template())) {
            throw invalid("当前仅允许 meta-agent 以原型指定模板创建七名研发数字员工");
        }
        if (draft.knowledge() != null && !draft.knowledge().isEmpty()) throw invalid("当前创建入口尚未接入知识库授权");
        if (draft.seedCount() != null && draft.seedCount() != 0) throw invalid("人工种子用例须在 H2 上传，不能仅填写数量");
        if (draft.tools() == null || draft.tools().size() != DEV_TOOLS.get(draft.targetAgent()).size()
                || !Set.copyOf(draft.tools()).equals(DEV_TOOLS.get(draft.targetAgent()))) {
            throw invalid("岗位工具必须与原型清单完全一致");
        }
        if (blank(draft.users()) || blank(draft.io()) || blank(draft.success())) throw invalid("场景、输入输出和成功标准必填");
        if (draft.dailyBudgetCny() == null || !Double.isFinite(draft.dailyBudgetCny()) || draft.dailyBudgetCny() < 1) throw invalid("日预算必须大于等于 1");
    }

    public ObjectNode get(String id) {
        jobs.job(id);
        var rows = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=?", (rs, n) -> read(rs.getString(1)), id);
        return rows.isEmpty() ? null : publicState(rows.getFirst());
    }

    public Map<String, Object> review(String id) {
        var state = get(id);
        if (state == null) return jobs.review(id);
        if ("WAITING_SEEDS".equals(state.path("phase").asText())) {
            return Map.of("jobId", id, "gate", "H2", "execution", state, "seedInputRequired", true,
                    "humanCount", 0, "minSeed", Math.max(1, jobs.settings().minSeed()),
                    "holdoutRatio", jobs.settings().holdoutPercent() / 100.0);
        }
        if ("WAITING_EVAL".equals(state.path("phase").asText())) {
            var cases = new ArrayList<Map<String, Object>>();
            for (var item : stateInternal(id).path("evalDraft").path("cases")) {
                cases.add(Map.of("caseId", item.path("caseId").asText(), "tag", item.path("tags").path(0).asText(),
                        "input", item.path("input").toString(), "expected", item.path("expected").toString(),
                        "reason", item.path("reason").asText()));
            }
            return Map.of("jobId", id, "gate", "H2", "execution", state, "seedInputRequired", false,
                    "humanCount", stateInternal(id).path("humanSeeds").size(),
                    "holdoutRatio", jobs.settings().holdoutPercent() / 100.0, "cases", cases);
        }
        if (!"WAITING".equals(state.path("phase").asText())) throw new KeelException(ErrorCode.RUN_NOT_RESUMABLE, "任务当前不在人工确认关口");
        var draft = state.path("draft");
        return Map.of("jobId", id, "gate", "H1", "execution", state,
                "specSheet", draft.path("spec"), "manifestYaml", draft.path("manifestYaml").asText(), "authList",
                jobs.job(id).tools().stream().map(tool -> Map.of("resource", tool.name(), "risk", tool.risk(), "owner", tool.owner())).toList());
    }

    public ObjectNode decide(String id, DevflowTypes.RequirementDecision decision) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            var phase = state.path("phase").asText();
            // A network retry of an already accepted confirmation returns the same execution.
            if ("CONFIRM".equals(decision.action()) && List.of("QUEUED", "RUNNING", "WAITING_SEEDS", "WAITING_EVAL",
                    "DELIVERING", "SANDBOX_PENDING", "SANDBOX_RUNNING", "WAITING_REVIEW", "DELIVERED").contains(phase)
                    && state.path("confirmedVersion").asInt(-1) == decision.expectedVersion()) return publicState(state);
            if (!"WAITING".equals(phase) || decision.expectedVersion() != state.path("version").asInt()) throw invalid("草稿版本已变化，请刷新后操作");
            if (!List.of("CONFIRM", "REVISE").contains(decision.action())) throw invalid("操作无效");
            if (blank(decision.users()) || blank(decision.io()) || blank(decision.success())) throw invalid("场景、输入输出和成功标准必填");
            var spec = (ObjectNode) state.path("draft").path("spec");
            spec.put("users", decision.users()).put("io", decision.io()).put("success", decision.success());
            var requirement = (ObjectNode) state.path("requirement");
            requirement.put("users", decision.users()).put("io", decision.io()).put("success", decision.success());
            String action;
            if ("REVISE".equals(decision.action())) {
                if (blank(decision.feedback())) throw invalid("退回时请填写修改意见");
                if (state.path("revisions").asInt() >= 3) throw invalid("已修改三轮，请确认草稿或取消任务");
                state.put("feedback", decision.feedback()).put("revisions", state.path("revisions").asInt() + 1)
                        .put("version", state.path("version").asInt() + 1);
                action = "DRAFT";
            } else {
                state.put("confirmedVersion", decision.expectedVersion());
                action = "EVAL";
            }
            state.put("phase", "EVAL".equals(action) ? "WAITING_SEEDS" : "QUEUED"); state.remove("error");
            save(id, state, action);
            jobs.progress(id, "EVAL".equals(action) ? "WAIT" : "QUEUED", "EVAL".equals(action) ? "H2" : "SPEC",
                    "EVAL".equals(action) ? "H1 已确认，请上传人工种子用例供元智能体扩充" : "已退回，等待元智能体按意见修改");
            return publicState(state);
        });
    }

    public ObjectNode uploadHumanSeeds(String id, List<DevflowTypes.SeedCase> cases) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            if (!"WAITING_SEEDS".equals(state.path("phase").asText())) throw invalid("当前不在人工种子用例上传阶段");
            int minimum = Math.max(1, jobs.settings().minSeed());
            if (cases == null || cases.size() < minimum || cases.size() > 100) throw invalid("人工种子用例数量不足或超过上限：至少 " + minimum + " 条");
            if (json.valueToTree(cases).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100_000) {
                throw invalid("人工种子用例总量不得超过 100KB");
            }
            var ids = new HashSet<String>();
            for (var item : cases) {
                if (item == null || item.caseId() == null || !item.caseId().matches("h-[A-Za-z0-9_-]{1,45}")
                        || !ids.add(item.caseId()) || item.input() == null || item.expected() == null
                        || item.tags() == null || item.tags().isEmpty()) throw invalid("人工用例需要唯一 h- 编号、输入、期望和标签");
            }
            state.set("humanSeeds", json.valueToTree(cases));
            state.put("phase", "QUEUED");
            save(id, state, "EVAL");
            jobs.progress(id, "QUEUED", "EVAL", "人工种子用例已上传，等待元智能体扩充");
            return publicState(state);
        });
    }

    public DevflowTypes.SeedResult acceptEval(String id, DevflowTypes.SeedAccept request, String actor) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            if (!"WAITING_EVAL".equals(state.path("phase").asText())) throw invalid("当前不在 H2 评测确认关口");
            var human = new ArrayList<DevflowTypes.SeedCase>();
            var selected = new ArrayList<DevflowTypes.SeedCase>();
            var ids = new ArrayList<String>();
            try {
                for (var item : state.path("humanSeeds")) {
                    var seed = json.treeToValue(item, DevflowTypes.SeedCase.class);
                    human.add(seed); selected.add(seed); ids.add(seed.caseId());
                }
                var wanted = request == null || request.acceptedCaseIds() == null ? List.<String>of() : request.acceptedCaseIds();
                if (wanted.stream().anyMatch(value -> value == null || value.isBlank())
                        || wanted.size() != Set.copyOf(wanted).size()) throw invalid("扩充用例编号无效或重复");
                var found = new HashSet<String>();
                for (var item : state.path("evalDraft").path("cases")) {
                    var seed = json.treeToValue(item, DevflowTypes.SeedCase.class);
                    if (wanted.contains(seed.caseId())) { selected.add(seed); ids.add(seed.caseId()); found.add(seed.caseId()); }
                }
                if (!found.equals(Set.copyOf(wanted))) throw invalid("只能采纳元智能体实际扩充的用例");
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw invalid("评测用例结构无效");
            }
            var hidden = HoldoutRules.split(id, selected, jobs.settings().holdoutPercent());
            var hiddenIds = hidden.stream().map(DevflowTypes.SeedCase::caseId).collect(java.util.stream.Collectors.toSet());
            state.set("visibleSeeds", json.valueToTree(selected.stream().filter(item -> !hiddenIds.contains(item.caseId())).toList()));
            var result = jobs.acceptSeeds(id, ids, selected, actor, human.size());
            state.put("acceptedAgentCount", result.acceptedAgentCount()).put("holdoutCount", result.holdoutCount());
            // After H2, exam content must live only in the trusted holdout store, never in meta-agent work.
            state.remove(List.of("humanSeeds", "evalDraft"));
            state.put("phase", "QUEUED");
            save(id, state, "DELIVER");
            jobs.progress(id, "QUEUED", "BUILD", "H2 已确认并切分隐藏考题，等待元智能体生成岗位代码");
            return result;
        });
    }

    public ObjectNode retry(String id) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            if (!"FAILED".equals(state.path("phase").asText())) throw invalid("只有失败的执行可以重试");
            state.put("phase", "QUEUED"); state.remove("error");
            String action = !state.has("confirmedVersion") ? "DRAFT" : state.has("acceptedAgentCount") ? "DELIVER" : "EVAL";
            save(id, state, action);
            jobs.progress(id, "QUEUED", "DELIVER".equals(action) ? "BUILD" : "SPEC", "已请求重试");
            return publicState(state);
        });
    }

    public DevflowTypes.Job cancel(String id, String actor) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            if (state.has("acceptedAgentCount") && !"FAILED".equals(state.path("phase").asText())) throw invalid("岗位代码交付已启动，请等待结果；已创建的仓库会保留");
            if ("CANCELLED".equals(state.path("phase").asText())) return jobs.job(id);
            state.put("phase", "CANCELLED");
            save(id, state, "DRAFT");
            return jobs.progress(id, "CANCEL", jobs.job(id).stage(), actor + "取消任务，忽略未完成的草稿结果");
        });
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void tick() {
        if (endpoint.isBlank() || token.isBlank()) return;
        String lease = UUID.randomUUID().toString();
        var claimed = jdbc.query("""
                UPDATE devflow_execution SET lease_token=?, lease_until=now()+interval '5 minutes',
                    state_json=jsonb_set(state_json,'{phase}',to_jsonb(CASE WHEN action='DELIVER' THEN 'DELIVERING'::text ELSE 'RUNNING'::text END))
                WHERE job_id=(SELECT job_id FROM devflow_execution
                    WHERE state_json->>'phase'='QUEUED' OR (state_json->>'phase' IN ('RUNNING','DELIVERING') AND lease_until<now())
                    ORDER BY updated_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING job_id, state_json::text, action
                """, (rs, n) -> new Work(rs.getString(1), read(rs.getString(2)), rs.getString(3)), lease);
        if (claimed.isEmpty()) return;
        var work = claimed.getFirst();
        try {
            var request = work.state.deepCopy();
            request.put("workflow", true).put("action", work.action);
            var answer = agent.invokeWork(endpoint, request.toString(), token, jobs.job(work.id).requester());
            var output = read(answer.text());
            complete(work, lease, output, answer.traceId(), null);
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(MetaAgentExecution.class).warn(
                    "meta-agent execution failed trace_id= agent=meta-agent job={} error={}", work.id, error.getClass().getSimpleName());
            complete(work, lease, null, null, "元智能体执行失败，请检查运行服务和 Git 服务后重试");
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 7000)
    public void tickSandbox() {
        if (sandbox == null) return;
        var ids = jdbc.query("""
                SELECT job_id FROM devflow_execution
                WHERE state_json->>'phase' IN ('SANDBOX_PENDING','SANDBOX_RUNNING')
                ORDER BY updated_at LIMIT 4
                """, (rs, n) -> rs.getString(1));
        for (var id : ids) {
            try {
                tx.executeWithoutResult(status -> {
                    var state = lock(id);
                    var phase = state.path("phase").asText();
                    if ("SANDBOX_PENDING".equals(phase)) {
                        try {
                            var delivery = state.path("delivery");
                            var repo = delivery.path("repo").asText().replaceFirst("^[^/]+/", "");
                            var run = sandbox.start(id, repo, delivery.path("sha").asText(), "meta-agent", Instant.now());
                            state.put("sandboxRunId", run.runId()).put("phase", "SANDBOX_RUNNING");
                            save(id, state, "DELIVER");
                            jobs.progress(id, "RUN", "BUILD", "隔离沙箱已启动，验证候选 PR commit " + delivery.path("sha").asText());
                        } catch (RuntimeException error) {
                            state.put("phase", "FAILED").put("error", "隔离沙箱启动失败，测试未运行");
                            save(id, state, "DELIVER");
                            jobs.progress(id, "FAIL", "BUILD", "隔离沙箱启动失败，测试未运行");
                        }
                    } else if ("SANDBOX_RUNNING".equals(phase)) {
                        try {
                            var run = sandbox.get(state.path("sandboxRunId").asText(), Instant.now());
                            if ("RUNNING".equals(run.status()) || "QUEUED".equals(run.status())) return;
                            var sha = state.path("delivery").path("sha").asText();
                            boolean passed = "DONE".equals(run.status()) && run.report().contains("sha=" + sha + " snapshot=ok tests=PASS");
                            state.put("sandboxStatus", passed ? "PASS" : "FAILED").put("phase", passed ? "WAITING_REVIEW" : "FAILED");
                            if (!passed) state.put("error", "隔离沙箱未通过；请查看任务报告并修复 PR");
                            save(id, state, "DELIVER");
                            jobs.progress(id, passed ? "WAIT" : "FAIL", passed ? "REVIEW" : "BUILD",
                                    passed ? "隔离沙箱已通过，等待 code-review 与人工 PR review" : "隔离沙箱测试失败，岗位候选不可上岗");
                        } catch (RuntimeException error) {
                            state.put("phase", "FAILED").put("sandboxStatus", "FAILED").put("error", "隔离沙箱结果读取失败或超时");
                            save(id, state, "DELIVER");
                            jobs.progress(id, "FAIL", "BUILD", "隔离沙箱结果读取失败或超时");
                        }
                    }
                });
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(MetaAgentExecution.class).warn(
                        "sandbox progression failed trace_id= agent=meta-agent job={} error={}", id, error.getClass().getSimpleName());
            }
        }
    }

    private void complete(Work work, String lease, ObjectNode output, String traceId, String error) {
        tx.executeWithoutResult(status -> {
            var rows = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=? AND lease_token=? FOR UPDATE",
                    (rs, n) -> read(rs.getString(1)), work.id, lease);
            if (rows.isEmpty()) return;
            var state = rows.getFirst();
            if (traceId != null) state.put("traceId", traceId);
            if (error != null) {
                state.put("phase", "FAILED").put("error", error);
                jobs.progress(work.id, "FAIL", work.action.equals("DELIVER") ? "BUILD" : work.action.equals("EVAL") ? "EVAL" : "SPEC", error);
            } else if (work.action.equals("DRAFT")) {
                if (!output.path("spec").isObject() || !output.path("manifest").isObject()
                        || blank(output.path("manifestYaml").asText())) throw invalid("草稿需求单或岗位说明书缺失");
                state.set("draft", output); state.put("phase", "WAITING");
                jobs.progress(work.id, "WAIT", "H1", "元智能体已生成第 " + state.path("version").asInt() + " 版草稿，等待需求确认");
            } else if (work.action.equals("EVAL")) {
                if (!output.path("cases").isArray() || output.path("cases").isEmpty()) throw invalid("评测扩充产物缺失");
                state.set("evalDraft", output); state.put("phase", "WAITING_EVAL");
                jobs.progress(work.id, "WAIT", "H2", "元智能体已扩充评测用例，等待 H2 人工确认");
            } else {
                if (output.path("number").asInt() <= 0 || !output.path("sha").asText().matches("[0-9a-f]{40}")
                        || blank(output.path("url").asText()) || blank(output.path("repo").asText())) throw invalid("Git 交付缺少 PR 或精确 commit");
                state.set("delivery", output); state.put("phase", "SANDBOX_PENDING");
                jobs.progress(work.id, "RUN", "BUILD", "岗位代码候选 PR 已交付，等待隔离沙箱执行实际代码测试");
            }
            save(work.id, state, work.action);
        });
    }

    private ObjectNode lock(String id) {
        var rows = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=? FOR UPDATE", (rs, n) -> read(rs.getString(1)), id);
        if (rows.isEmpty()) throw invalid("该任务未接入元智能体执行");
        return rows.getFirst();
    }

    private void save(String id, ObjectNode state, String action) {
        jdbc.update("UPDATE devflow_execution SET state_json=?::jsonb, action=?, lease_token=NULL, lease_until=NULL, updated_at=now() WHERE job_id=?", state.toString(), action, id);
    }

    private ObjectNode read(String value) {
        try { return (ObjectNode) json.readTree(value); } catch (Exception e) { throw invalid("执行产物不是 JSON 对象"); }
    }

    private ObjectNode publicState(ObjectNode state) {
        var result = state.deepCopy(); result.remove(List.of("catalog", "feedback", "confirmedVersion", "humanSeeds", "evalDraft")); return result;
    }

    private ObjectNode stateInternal(String id) {
        var rows = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=?", (rs, n) -> read(rs.getString(1)), id);
        if (rows.isEmpty()) throw invalid("任务执行状态不存在");
        return rows.getFirst();
    }

    private record Work(String id, ObjectNode state, String action) {}
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static KeelException invalid(String message) { return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message); }
}
