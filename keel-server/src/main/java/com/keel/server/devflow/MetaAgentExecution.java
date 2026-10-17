package com.keel.server.devflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.approval.service.ApprovalService;
import com.keel.server.approval.service.ApprovalPolicyEngine;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.registry.service.WizardCatalog;
import io.fabric8.kubernetes.client.KubernetesClient;
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
import java.util.LinkedHashMap;
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
            "dev-lead", Set.of("devflow.job.read", "devflow.stage.report", "devflow.batch.read"),
            "spec-agent", Set.of("devflow.catalog.search", "devflow.stage.report"),
            "dev-agent", Set.of("sandbox.run", "git.repo.create", "git.branch.push", "git.pr.open"),
            "eval-agent", Set.of("devflow.job.read", "sandbox.run", "devflow.stage.report"),
            "code-review", Set.of("git.pr.diff", "git.pr.comment"),
            "ci-doctor", Set.of("ci.log.fetch", "keel.gate.report.read"),
            "release-agent", Set.of("ci.workflow.dispatch", "keel.gate.report.read", "git.pr.merge"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final DevflowService jobs;
    private final WizardCatalog catalog;
    private final AgentEndpointClient agent;
    private final SandboxService sandbox;
    private final GitSnapshotClient git;
    private final ApprovalService approvals;
    private final KubernetesClient cluster;
    private final String endpoint;
    private final String token;

    @Autowired
    public MetaAgentExecution(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
                              DevflowService jobs, WizardCatalog catalog, AgentEndpointClient agent,
                              SandboxService sandbox, GitSnapshotClient git, ApprovalService approvals,
                              KubernetesClient cluster,
                              @Value("${KEEL_META_AGENT_URL:}") String endpoint,
                              @Value("${KEEL_META_AGENT_TOKEN:}") String token) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(transactions);
        this.jobs = jobs; this.catalog = catalog; this.agent = agent; this.sandbox = sandbox; this.git = git;
        this.approvals = approvals;
        this.cluster = cluster;
        this.endpoint = endpoint; this.token = token;
    }

    MetaAgentExecution(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
                       DevflowService jobs, WizardCatalog catalog, AgentEndpointClient agent,
                       String endpoint, String token) {
        this(jdbc, json, transactions, jobs, catalog, agent, null, null, null, null, endpoint, token);
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

    public ObjectNode grantTool(String id, String toolName, String versionRange) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            if (!state.has("acceptedAgentCount") || "CANCELLED".equals(state.path("phase").asText())) {
                throw invalid("H2 确认后才能批准候选员工工具权限");
            }
            if (blank(toolName) || blank(versionRange) || versionRange.length() > 128) {
                throw invalid("工具名称和版本不能为空");
            }
            var selected = new HashSet<String>();
            state.path("requirement").path("tools").forEach(item -> selected.add(item.asText()));
            if (!selected.contains(toolName)) throw invalid("只能批准 H1 确认的岗位工具");
            var versions = jdbc.query("""
                    SELECT t.scope, t.status, v.risk, v.approval_policy_id
                    FROM tool t JOIN tool_version v ON v.tool_name = t.name
                    WHERE t.name = ? AND v.version = ?
                    """, (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4)},
                    toolName, versionRange);
            if (versions.size() != 1 || !"SHARED".equals(versions.getFirst()[0])
                    || !"ONLINE".equals(versions.getFirst()[1])
                    || ("HIGH".equals(versions.getFirst()[2]) && versions.getFirst()[3] == null)) {
                throw invalid("工具版本不可授权，或高风险审批策略未绑定");
            }
            var previous = jdbc.query("SELECT version_range FROM devflow_tool_grant WHERE job_id=? AND tool_name=?",
                    (rs, n) -> rs.getString(1), id, toolName);
            if (!previous.isEmpty()) {
                if (!previous.getFirst().equals(versionRange)) throw invalid("工具已有不同版本的 H3 授权");
                return publicState(state);
            }
            jdbc.update("INSERT INTO devflow_tool_grant(job_id,tool_name,version_range,granted_by) VALUES (?,?,?,?)",
                    id, toolName, versionRange, ConsolePrincipal.current().username());
            return publicState(state);
        });
    }

    public ObjectNode refreshHumanReview(String id) {
        requireAdmin();
        var state = stateInternal(id);
        if (!"WAITING_REVIEW".equals(state.path("phase").asText()) || git == null) {
            throw invalid("候选代码尚未进入人工 PR 评审阶段");
        }
        var delivery = state.path("delivery");
        var repo = delivery.path("repo").asText().replaceFirst("^[^/]+/", "");
        var evidence = git.reviewStatus(id, repo, delivery.path("number").asInt(), delivery.path("sha").asText());
        return tx.execute(status -> {
            var current = lock(id);
            if (!"WAITING_REVIEW".equals(current.path("phase").asText())
                    || !delivery.path("sha").asText().equals(current.path("delivery").path("sha").asText())) {
                throw invalid("候选提交已变化，请重新核验人工评审");
            }
            current.set("humanReview", evidence);
            save(id, current, "DELIVER");
            return publicState(current);
        });
    }

    public ObjectNode runCodeReview(String id) {
        requireAdmin();
        var state = stateInternal(id);
        if (!"WAITING_REVIEW".equals(state.path("phase").asText())
                || !state.path("humanReview").path("approved").asBoolean(false)
                || !"PASS".equals(state.path("sandboxStatus").asText()) || git == null) {
            throw invalid("代码评审需先通过沙箱及当前提交的人工 PR 审批");
        }
        var required = new HashSet<String>();
        state.path("requirement").path("tools").forEach(item -> required.add(item.asText()));
        var grants = jdbc.query("SELECT tool_name FROM devflow_tool_grant WHERE job_id=?",
                (rs, row) -> rs.getString(1), id);
        if (!Set.copyOf(grants).equals(required)) throw invalid("H3 工具授权尚未全部完成");
        var delivery = state.path("delivery");
        var sha = delivery.path("sha").asText();
        var repo = delivery.path("repo").asText().replaceFirst("^[^/]+/", "");
        try {
            var request = state.deepCopy();
            request.put("workflow", true).put("action", "REVIEW");
            request.set("snapshot", json.readTree(git.fetch(id, repo, sha)));
            var answer = agent.invokeWork(endpoint, request.toString(), token, jobs.job(id).requester());
            var report = read(answer.text());
            if (!sha.equals(report.path("sha").asText()) || !report.path("approved").isBoolean()
                    || !report.path("checks").isObject() || !report.path("findings").isArray()) {
                throw invalid("模型代码评审结果与候选提交不一致");
            }
            validateReviewChecks(report);
            for (var finding : report.path("findings")) {
                if (finding.path("severity").asText().matches("HIGH|MID") && report.path("approved").asBoolean()) {
                    throw invalid("模型代码评审结论与风险发现冲突");
                }
            }
            return tx.execute(status -> {
                var current = lock(id);
                if (!"WAITING_REVIEW".equals(current.path("phase").asText())
                        || !sha.equals(current.path("delivery").path("sha").asText())
                        || !current.path("humanReview").path("approved").asBoolean(false)) {
                    throw invalid("候选提交或人工评审已变化，请重新运行代码评审");
                }
                current.set("modelReview", report);
                current.put("modelReviewTraceId", answer.traceId());
                if (report.path("approved").asBoolean()) {
                    current.put("phase", "STAGING_READY");
                    jobs.progress(id, "RUN", "GATE", "模型代码评审与人工 PR review 已通过，等待 staging 部署和门禁");
                } else {
                    jobs.progress(id, "WAIT", "REVIEW", "模型代码评审发现问题，请在同一 PR 修复后重验");
                }
                save(id, current, "REVIEW");
                return publicState(current);
            });
        } catch (KeelException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("代码评审服务不可用或未返回可信结果");
        }
    }

    static void validateReviewChecks(JsonNode report) {
        for (var check : List.of("modelAccess", "toolAuthorization", "audit", "visibleCaseOverfit",
                "releaseApproval", "behaviorTests", "hardcodedPrompt")) {
            if (!report.path("checks").path(check).isBoolean()) throw invalid("代码评审维度不完整");
            if (report.path("approved").asBoolean() && !report.path("checks").path(check).asBoolean()) {
                throw invalid("模型代码评审结论与分项结果冲突");
            }
        }
    }

    /** Called only after HoldoutService authenticates and scores the CI identity's hidden result. */
    public ObjectNode recordStagingGate(DevflowController.HoldoutSubmission result) {
        if (result == null || !"staging".equals(result.env())
                || result.gateRunId() == null || !result.gateRunId().matches("[0-9a-f]{32}")
                || result.sha() == null || !result.sha().matches("[0-9a-f]{40}")
                || result.image() == null || !result.image().matches("[^\\s]+@sha256:[0-9a-f]{64}")
                || result.visibleByTag() == null || result.visibleByTag().isEmpty()
                || result.visibleByTag().values().stream().anyMatch(score -> score == null
                    || !Double.isFinite(score) || score < HoldoutRules.MIN_SCORE || score > 1)) {
            throw invalid("staging 门禁需要 CI 绑定的候选提交与可见用例分数");
        }
        return tx.execute(status -> {
            var state = lock(result.jobId());
            if (!"STAGING_READY".equals(state.path("phase").asText())
                    || !result.sha().equals(state.path("delivery").path("sha").asText())
                    || !state.path("humanReview").path("approved").asBoolean(false)
                    || !state.path("modelReview").path("approved").asBoolean(false)) {
                throw invalid("候选提交或评审状态与 staging 门禁不一致");
            }
            var target = state.path("requirement").path("target_agent").asText();
            var registered = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM agent_version WHERE agent_name=? AND env='staging')
                    """, Boolean.class, target);
            var provisioned = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM agent_resource WHERE agent_name=? AND env='staging' AND status='ACTIVE')
                    """, Boolean.class, target);
            if (!Boolean.TRUE.equals(registered) || !Boolean.TRUE.equals(provisioned)) {
                throw invalid("员工尚未登记并开通 staging 资源");
            }
            if (!DeploymentEvidence.ready(cluster, target, "staging", result.sha(), result.image())) {
                throw invalid("staging 预览 Deployment 未就绪，或镜像与候选提交不一致");
            }
            var hidden = jdbc.query("SELECT passed, score FROM devflow_holdout_result WHERE job_id=?",
                    (rs, row) -> new Object[]{rs.getBoolean(1), rs.getDouble(2)}, result.jobId());
            if (hidden.size() != 1 || !Boolean.TRUE.equals(hidden.getFirst()[0])) {
                throw invalid("隐藏考题尚未通过");
            }
            var submitted = result.byTag() == null ? 0 : result.byTag().stream().mapToInt(HoldoutRules.TagScore::count).sum();
            if (submitted < state.path("holdoutCount").asInt(1)) throw invalid("隐藏考题回报数量不足");
            var visibleCounts = new LinkedHashMap<String, Integer>();
            for (var seed : state.path("visibleSeeds")) {
                for (var tag : seed.path("tags")) visibleCounts.merge(tag.asText(), 1, Integer::sum);
            }
            if (visibleCounts.isEmpty() || !visibleCounts.keySet().equals(result.visibleByTag().keySet())) {
                throw invalid("可见用例 tag 分数与 H2 已确认用例不一致");
            }
            double hiddenScore = (double) hidden.getFirst()[1];
            int visibleTotal = visibleCounts.values().stream().mapToInt(Integer::intValue).sum();
            double visibleScore = visibleCounts.entrySet().stream()
                    .mapToDouble(item -> item.getValue() * result.visibleByTag().get(item.getKey())).sum() / visibleTotal;
            if (Math.abs(visibleScore - hiddenScore) > 0.10) throw invalid("可见与隐藏用例分差超过 0.10");
            var gate = state.putObject("gate");
            gate.put("runId", result.gateRunId()).put("sha", result.sha()).put("image", result.image()).put("env", "staging")
                    .put("passed", true).put("visibleScore", visibleScore).put("hiddenScore", hiddenScore);
            gate.set("visibleByTag", json.valueToTree(result.visibleByTag()));
            state.put("phase", "STAGING_GATE_PASSED");
            save(result.jobId(), state, "GATE");
            jobs.progress(result.jobId(), "WAIT", "H4", "staging 可见与隐藏考题门禁通过，等待 H4 合并审批");
            return publicState(state);
        });
    }

    /** The CI identity submits a fresh prod gate for the exact commit returned by the H4 merge. */
    public ObjectNode recordProdGate(DevflowController.HoldoutSubmission result) {
        if (result == null || !"prod".equals(result.env())
                || result.gateRunId() == null || !result.gateRunId().matches("[0-9a-f]{32}")
                || result.sha() == null || !result.sha().matches("[0-9a-f]{40}")
                || result.image() == null || !result.image().matches("[^\\s]+@sha256:[0-9a-f]{64}")
                || result.visibleByTag() == null || result.visibleByTag().isEmpty()
                || result.visibleByTag().values().stream().anyMatch(score -> score == null
                    || !Double.isFinite(score) || score < HoldoutRules.MIN_SCORE || score > 1)) {
            throw invalid("prod 门禁需要 CI 绑定的合并提交与可见用例分数");
        }
        return tx.execute(status -> {
            var state = lock(result.jobId());
            if (!"RELEASING".equals(state.path("phase").asText())
                    || !state.path("gate").path("passed").asBoolean(false)
                    || !result.sha().equals(state.path("mergeSha").asText())
                    || result.gateRunId().equals(state.path("gate").path("runId").asText())) {
                throw invalid("prod 门禁必须在 H4 合并后对合并提交重新执行");
            }
            var h4 = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM approval_request
                        WHERE id=? AND devflow_job_id=? AND devflow_gate='H4'
                          AND subject_type='tool.call' AND subject_ref='git.pr.merge' AND status='APPROVED')
                    """, Boolean.class, ApprovalPolicyEngine.parseId(state.path("h4ApprovalId").asText()), result.jobId());
            if (!Boolean.TRUE.equals(h4)) throw invalid("H4 审批证据已失效");
            var hidden = jdbc.query("SELECT passed, score FROM devflow_holdout_result WHERE job_id=?",
                    (rs, row) -> new Object[]{rs.getBoolean(1), rs.getDouble(2)}, result.jobId());
            if (hidden.size() != 1 || !Boolean.TRUE.equals(hidden.getFirst()[0])) throw invalid("prod 隐藏考题尚未通过");
            var visibleCounts = new LinkedHashMap<String, Integer>();
            for (var seed : state.path("visibleSeeds")) {
                for (var tag : seed.path("tags")) visibleCounts.merge(tag.asText(), 1, Integer::sum);
            }
            if (visibleCounts.isEmpty() || !visibleCounts.keySet().equals(result.visibleByTag().keySet())) {
                throw invalid("prod 可见用例 tag 与 H2 已确认用例不一致");
            }
            var visibleTotal = visibleCounts.values().stream().mapToInt(Integer::intValue).sum();
            var visibleScore = visibleCounts.entrySet().stream()
                    .mapToDouble(item -> item.getValue() * result.visibleByTag().get(item.getKey())).sum() / visibleTotal;
            var hiddenScore = (double) hidden.getFirst()[1];
            if (Math.abs(visibleScore - hiddenScore) > 0.10) throw invalid("prod 可见与隐藏用例分差超过 0.10");
            var prodGate = state.putObject("prodGate");
            prodGate.put("runId", result.gateRunId()).put("sha", result.sha()).put("image", result.image()).put("env", "prod")
                    .put("passed", true).put("visibleScore", visibleScore).put("hiddenScore", hiddenScore);
            prodGate.set("visibleByTag", json.valueToTree(result.visibleByTag()));
            save(result.jobId(), state, "RELEASE");
            jobs.progress(result.jobId(), "RUN", "RELEASE", "合并提交的 prod 门禁与隐藏考题通过，等待 CI 发布");
            return publicState(state);
        });
    }

    public ObjectNode requestH4(String id) {
        requireAdmin();
        if (approvals == null) throw invalid("审批服务不可用");
        return tx.execute(status -> {
            var state = lock(id);
            if ("WAITING_H4".equals(state.path("phase").asText()) && state.has("h4ApprovalId")) {
                return publicState(state);
            }
            if (!"STAGING_GATE_PASSED".equals(state.path("phase").asText())
                    || !state.path("gate").path("passed").asBoolean(false)
                    || !state.path("delivery").path("sha").asText()
                        .equals(state.path("gate").path("sha").asText())) {
                throw invalid("只有通过 staging 门禁的候选提交可以申请 H4");
            }
            var target = state.path("requirement").path("target_agent").asText();
            var approval = approvals.openDevflowMerge(id, target, state.path("delivery").path("sha").asText(),
                    ConsolePrincipal.current().username());
            state.put("h4ApprovalId", approval.id()).put("phase", "WAITING_H4");
            save(id, state, "H4");
            jobs.progress(id, "WAIT", "H4", "H4 高风险合并审批已提交，等待平台管理员处理");
            return publicState(state);
        });
    }

    public ObjectNode refreshH4(String id) {
        requireAdmin();
        var state = stateInternal(id);
        if (!"WAITING_H4".equals(state.path("phase").asText()) || git == null) {
            throw invalid("任务尚未等待 H4 审批");
        }
        var sha = state.path("delivery").path("sha").asText();
        var approvalId = state.path("h4ApprovalId").asText();
        var rows = jdbc.query("""
                SELECT status, payload_digest FROM approval_request
                WHERE id=? AND devflow_job_id=? AND devflow_gate='H4'
                  AND subject_type='tool.call' AND subject_ref='git.pr.merge'
                  AND agent_name=?
                """, (rs, row) -> new String[]{rs.getString(1), rs.getString(2)},
                ApprovalPolicyEngine.parseId(approvalId), id,
                state.path("requirement").path("target_agent").asText());
        if (rows.size() != 1 || !sha.equals(rows.getFirst()[1])) throw invalid("H4 审批与候选提交不一致");
        var verdict = rows.getFirst()[0];
        if ("PENDING".equals(verdict)) return publicState(state);
        if ("REJECTED".equals(verdict) || "EXPIRED".equals(verdict)) {
            return tx.execute(status -> {
                var current = lock(id);
                if (!"WAITING_H4".equals(current.path("phase").asText())) return publicState(current);
                current.put("phase", "FAILED").put("error", "H4 合并审批已驳回或过期，候选 PR 保留待人工接管");
                save(id, current, "H4");
                jobs.progress(id, "FAIL", "H4", "H4 合并审批已驳回或过期，未执行 Git 合并");
                return publicState(current);
            });
        }
        if (!"APPROVED".equals(verdict)) throw invalid("H4 审批状态无效");
        var delivery = state.path("delivery");
        var merged = git.mergeApproved(id, delivery.path("repo").asText().replaceFirst("^[^/]+/", ""),
                delivery.path("number").asInt(), sha);
        return tx.execute(status -> {
            var current = lock(id);
            if (!"WAITING_H4".equals(current.path("phase").asText())
                    || !sha.equals(current.path("delivery").path("sha").asText())
                    || !approvalId.equals(current.path("h4ApprovalId").asText())) {
                throw invalid("H4 候选提交或审批单已变化");
            }
            current.put("mergeSha", merged.path("sha").asText()).put("phase", "RELEASING");
            save(id, current, "RELEASE");
            jobs.progress(id, "RUN", "RELEASE", "H4 已批准且受保护 PR 已合并，等待生产门禁及 CI 发布");
            return publicState(current);
        });
    }

    public Map<String, Object> review(String id) {
        var state = get(id);
        if (state == null) return jobs.review(id);
        if ("WAITING_SEEDS".equals(state.path("phase").asText())) {
            return Map.of("jobId", id, "gate", "H2", "execution", state, "seedInputRequired", true,
                    "humanCount", 0, "minSeed", Math.max(30, jobs.settings().minSeed()),
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
        if ("WAITING_H4".equals(state.path("phase").asText())) {
            var response = new LinkedHashMap<String, Object>();
            response.put("jobId", id);
            response.put("gate", "H4");
            response.put("execution", state);
            response.put("approvalId", state.path("h4ApprovalId").asText());
            var gate = state.path("gate");
            var reports = jdbc.query("SELECT by_tag::text FROM devflow_holdout_result WHERE job_id=? AND passed",
                    (rs, row) -> readArray(rs.getString(1)), id);
            var hiddenByTag = new LinkedHashMap<String, Double>();
            if (!reports.isEmpty()) {
                for (var item : reports.getFirst()) hiddenByTag.put(item.path("tag").asText(), item.path("score").asDouble());
            }
            var byTag = new ArrayList<Map<String, Object>>();
            gate.path("visibleByTag").fields().forEachRemaining(entry -> byTag.add(Map.of(
                    "tag", entry.getKey(), "visible", entry.getValue().asDouble(),
                    "holdout", hiddenByTag.getOrDefault(entry.getKey(), 0.0))));
            response.put("gateReport", Map.of("visible", gate.path("visibleScore").asDouble(),
                    "holdout", gate.path("hiddenScore").asDouble(), "minScore", HoldoutRules.MIN_SCORE,
                    "byTag", byTag));
            return response;
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
            // DEV onboarding requires at least 30 human-authored seed cases per role.
            int minimum = Math.max(30, jobs.settings().minSeed());
            if (cases == null || cases.size() < minimum || cases.size() > 100) throw invalid("人工种子用例数量不足或超过上限：至少 " + minimum + " 条");
            if (json.valueToTree(cases).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100_000) {
                throw invalid("人工种子用例总量不得超过 100KB");
            }
            var ids = new HashSet<String>();
            for (var item : cases) {
                if (item == null || item.caseId() == null || !item.caseId().matches("h-[A-Za-z0-9_-]{1,45}")
                        || !ids.add(item.caseId()) || item.input() == null || item.expected() == null
                        || item.tags() == null || item.tags().isEmpty()
                        || item.tags().stream().anyMatch(tag -> tag == null || tag.isBlank())) {
                    throw invalid("人工用例需要唯一 h- 编号、输入、期望和标签");
                }
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
            if ("WAITING_REVIEW".equals(state.path("phase").asText())) {
                if (git == null || !"PASS".equals(state.path("sandboxStatus").asText())) {
                    throw invalid("只有沙箱通过的候选可以刷新交付");
                }
                int attempt = state.path("sandboxAttempts").size() + 1;
                int refreshes = state.path("infrastructureRefreshes").asInt(0);
                boolean infrastructureOnly = canRefreshInfrastructure(refreshes);
                int reviewRepairs = state.path("reviewRepairRounds").asInt(0);
                boolean ciRefresh = !infrastructureOnly && reviewRepairs >= 1 && attempt == 7;
                if (!infrastructureOnly && !ciRefresh && reviewRepairs >= 1)
                    throw invalid("评审阶段岗位功能修复已达到一次上限");
                var delivery = state.path("delivery");
                var sha = delivery.path("sha").asText();
                var repo = delivery.path("repo").asText().replaceFirst("^[^/]+/", "");
                git.reviewStatus(id, repo, delivery.path("number").asInt(), sha);
                var previous = previousCandidate(json, read(git.fetch(id, repo, sha)));
                var repair = state.putObject("repair");
                repair.put("attempt", attempt).put("failedSha", sha).put("infrastructureOnly", infrastructureOnly || ciRefresh)
                        .put("ciRefresh", ciRefresh).put("reviewRepair", !infrastructureOnly && !ciRefresh)
                        .put("report", ciRefresh
                                ? "公开候选 PR 的 CI 误在人工评审及 staging 开通前启动受管门禁；只刷新无密钥预检工作流，保留岗位代码"
                                : infrastructureOnly
                                ? "交付仓库 CI 工作流无效；保留岗位代码并刷新可信模板"
                                : "H2 可见用例是自然语言输入且期望不同的可核对结论。当前候选固定返回 H1 阻塞，无法通过岗位行为门禁；请修复岗位逻辑与测试，不能硬编码题目。可信模板已提供评分函数。");
                repair.set("previousCandidate", previous);
                if (infrastructureOnly) state.put("infrastructureRefreshes", refreshes + 1);
                else if (!ciRefresh) state.put("reviewRepairRounds", reviewRepairs + 1);
                state.remove(List.of("humanReview", "modelReview", "modelReviewTraceId", "sandboxStatus", "sandboxRunId"));
                state.put("phase", "QUEUED");
                save(id, state, "DELIVER");
                jobs.progress(id, "QUEUED", "BUILD", "第 " + attempt + (ciRefresh
                        ? " 轮修复候选 CI，重新验证精确提交" : infrastructureOnly
                        ? " 轮刷新候选交付，重新验证精确提交" : " 轮修复岗位功能，重新验证精确提交"));
                return publicState(state);
            }
            if (!"FAILED".equals(state.path("phase").asText())) throw invalid("只有失败的执行可以重试");
            if (sandboxLimitBlocksRetry(state)) {
                throw invalid("沙箱修复已达到三轮上限");
            }
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
                    "meta-agent execution failed trace_id= agent=meta-agent job={} error={} message={}",
                    work.id, error.getClass().getSimpleName(), error.getMessage());
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
                            int attempt = state.path("sandboxAttempts").size() + 1;
                            var attempts = state.withArray("sandboxAttempts");
                            attempts.addObject().put("attempt", attempt).put("sha", sha).put("runId", run.runId())
                                    .put("status", passed ? "PASS" : "FAILED").put("report", run.report());
                            state.put("sandboxStatus", passed ? "PASS" : "FAILED");
                            if (passed) {
                                state.put("phase", "WAITING_REVIEW");
                                state.remove("repair");
                                save(id, state, "DELIVER");
                                jobs.progress(id, "WAIT", "REVIEW", "第 " + attempt + " 轮沙箱通过，等待 code-review 与人工 PR review");
                            } else if (repairableSandbox(run.status(), run.report(), sha, attempt) && git != null) {
                                var repo = state.path("delivery").path("repo").asText().replaceFirst("^[^/]+/", "");
                                var snapshot = read(git.fetch(id, repo, sha));
                                var previous = previousCandidate(json, snapshot);
                                var repair = state.putObject("repair");
                                repair.put("attempt", attempt + 1).put("failedSha", sha).put("report", run.report());
                                repair.set("previousCandidate", previous);
                                state.put("phase", "QUEUED");
                                save(id, state, "DELIVER");
                                jobs.progress(id, "QUEUED", "BUILD", "第 " + attempt + " 轮沙箱测试失败，准备第 " + (attempt + 1) + " 轮修复");
                            } else {
                                state.put("phase", "FAILED").put("error", attempt >= 3
                                        ? "三轮隔离沙箱测试仍未通过，岗位候选不可交付" : "隔离沙箱未完成岗位测试，候选不可交付");
                                save(id, state, "DELIVER");
                                jobs.progress(id, "FAIL", "BUILD", "第 " + attempt + " 轮沙箱失败，修复循环已停止");
                            }
                        } catch (RuntimeException error) {
                            var message = error instanceof KeelException keelError
                                    && keelError.code() == ErrorCode.DEVFLOW_SANDBOX_TIMEOUT
                                    ? error.getMessage() : "隔离沙箱结果读取失败或超时";
                            state.put("phase", "FAILED").put("sandboxStatus", "FAILED").put("error", message);
                            save(id, state, "DELIVER");
                            jobs.progress(id, "FAIL", "BUILD", message);
                        }
                    }
                });
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(MetaAgentExecution.class).warn(
                        "sandbox progression failed trace_id= agent=meta-agent job={} error={}", id, error.getClass().getSimpleName());
            }
        }
    }

    static boolean repairableSandbox(String status, String report, String sha, int attempt) {
        return attempt < 3 && "FAILED".equals(status) && report != null
                && report.startsWith("sha=" + sha + " snapshot=ok tests=FAIL\n");
    }

    static boolean canRefreshInfrastructure(int refreshes) {
        return refreshes >= 0 && refreshes < 2;
    }

    static boolean sandboxLimitBlocksRetry(JsonNode state) {
        if (state.path("repair").path("ciRefresh").asBoolean(false)) {
            return "FAILED".equals(state.path("sandboxStatus").asText())
                    && state.path("sandboxAttempts").size() >= 7;
        }
        if (state.path("repair").path("reviewRepair").asBoolean(false)) {
            return "FAILED".equals(state.path("sandboxStatus").asText())
                    && state.path("sandboxAttempts").size() >= 6;
        }
        return state.path("sandboxAttempts").size() >= 3
                && !state.path("repair").path("infrastructureOnly").asBoolean(false);
    }

    static ObjectNode previousCandidate(ObjectMapper json, JsonNode snapshot) {
        var previous = json.createObjectNode();
        var files = previous.putArray("files");
        var needed = Set.of("role.py", "prompts/role.md", "tests/test_role.py");
        for (var file : snapshot.path("files")) {
            if (needed.contains(file.path("path").asText())) files.add(file);
        }
        if (files.size() != needed.size()) throw invalid("上轮候选快照缺少修复所需文件");
        return previous;
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
                if (state.has("repair") && output.path("sha").asText().equals(state.path("repair").path("failedSha").asText())) {
                    throw invalid("修复候选必须生成新的 Git 提交");
                }
                state.set("delivery", output); state.put("phase", "SANDBOX_PENDING");
                state.remove(List.of("sandboxRunId", "sandboxStatus"));
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

    private JsonNode readArray(String value) {
        try {
            var node = json.readTree(value);
            if (node.isArray()) return node;
        } catch (Exception ignored) { }
        throw invalid("隐藏考题聚合报告损坏");
    }

    private ObjectNode publicState(ObjectNode state) {
        var result = state.deepCopy();
        result.remove(List.of("catalog", "feedback", "confirmedVersion", "humanSeeds", "evalDraft", "repair"));
        if (state.has("acceptedAgentCount")) {
            var grants = jdbc.query("SELECT tool_name, version_range, granted_by FROM devflow_tool_grant WHERE job_id=? ORDER BY tool_name",
                    (rs, n) -> Map.of("toolName", rs.getString(1), "versionRange", rs.getString(2),
                            "grantedBy", rs.getString(3)), state.path("jobId").asText());
            result.set("toolGrants", json.valueToTree(grants));
            var approved = grants.stream().map(row -> row.get("toolName")).collect(java.util.stream.Collectors.toSet());
            var pending = new ArrayList<String>();
            state.path("requirement").path("tools").forEach(item -> {
                if (!approved.contains(item.asText())) pending.add(item.asText());
            });
            result.set("pendingToolGrants", json.valueToTree(pending));
        }
        return result;
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
