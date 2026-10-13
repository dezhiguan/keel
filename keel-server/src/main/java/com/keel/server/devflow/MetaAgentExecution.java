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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable minimal workflow. No user ticket or in-memory SDK resume token is kept between H1 turns. */
@Service
public class MetaAgentExecution {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final DevflowService jobs;
    private final WizardCatalog catalog;
    private final AgentEndpointClient agent;
    private final String endpoint;
    private final String token;

    public MetaAgentExecution(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
                              DevflowService jobs, WizardCatalog catalog, AgentEndpointClient agent,
                              @Value("${KEEL_META_AGENT_URL:}") String endpoint,
                              @Value("${KEEL_META_AGENT_TOKEN:}") String token) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(transactions);
        this.jobs = jobs; this.catalog = catalog; this.agent = agent;
        this.endpoint = endpoint; this.token = token;
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
                    .put("io", draft.io()).put("success", draft.success()).put("template", "tool-agent")
                    .put("dailyBudgetCny", draft.dailyBudgetCny()).put("owner", job.ownerOrg() + " / " + actor);
            requirement.set("tools", json.valueToTree(draft.tools() == null ? List.of() : draft.tools()));
            state.set("catalog", json.valueToTree(tools));
            jdbc.update("INSERT INTO devflow_execution(job_id,state_json,action) VALUES (?,?::jsonb,'DRAFT')", job.jobId(), state.toString());
            return jobs.progress(job.jobId(), "QUEUED", "SPEC", "已排队，等待元智能体生成需求草稿");
        });
    }

    public static void validate(DevflowTypes.Draft draft) {
        if (!"DEV".equals(draft.layer()) || !"CREATE".equals(draft.kind()) || !"COLLAB".equals(draft.mode())
                || !"tool-agent".equals(draft.template()) || "meta-agent".equals(draft.targetAgent())) {
            throw invalid("当前仅开放研发智能体新建、人机协作、Python tool-agent 骨架");
        }
        if (draft.knowledge() != null && !draft.knowledge().isEmpty()) throw invalid("骨架交付暂不支持知识库配置");
        if (draft.seedCount() != null && draft.seedCount() != 0) throw invalid("骨架交付暂不支持种子评测");
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
        if (!"WAITING".equals(state.path("phase").asText())) throw new KeelException(ErrorCode.RUN_NOT_RESUMABLE, "任务当前不在需求确认关口");
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
            if ("CONFIRM".equals(decision.action()) && List.of("QUEUED", "DELIVERING", "DELIVERED").contains(phase)
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
                action = "DELIVER";
            }
            state.put("phase", "QUEUED"); state.remove("error");
            save(id, state, action);
            jobs.progress(id, "QUEUED", "DELIVER".equals(action) ? "BUILD" : "SPEC",
                    "DELIVER".equals(action) ? "需求已确认，等待生成骨架 PR" : "已退回，等待元智能体按意见修改");
            return publicState(state);
        });
    }

    public ObjectNode retry(String id) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            if (!"FAILED".equals(state.path("phase").asText())) throw invalid("只有失败的执行可以重试");
            state.put("phase", "QUEUED"); state.remove("error");
            String action = state.has("confirmedVersion") ? "DELIVER" : "DRAFT";
            save(id, state, action);
            jobs.progress(id, "QUEUED", "DELIVER".equals(action) ? "BUILD" : "SPEC", "已请求重试");
            return publicState(state);
        });
    }

    public DevflowTypes.Job cancel(String id, String actor) {
        requireAdmin();
        return tx.execute(status -> {
            var state = lock(id);
            if (state.has("confirmedVersion") && !"FAILED".equals(state.path("phase").asText())) throw invalid("骨架交付已启动，请等待结果；已创建的仓库会保留");
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

    private void complete(Work work, String lease, ObjectNode output, String traceId, String error) {
        tx.executeWithoutResult(status -> {
            var rows = jdbc.query("SELECT state_json::text FROM devflow_execution WHERE job_id=? AND lease_token=? FOR UPDATE",
                    (rs, n) -> read(rs.getString(1)), work.id, lease);
            if (rows.isEmpty()) return;
            var state = rows.getFirst();
            if (traceId != null) state.put("traceId", traceId);
            if (error != null) {
                state.put("phase", "FAILED").put("error", error);
                jobs.progress(work.id, "FAIL", work.action.equals("DELIVER") ? "BUILD" : "SPEC", error);
            } else if (work.action.equals("DRAFT")) {
                if (!output.has("spec") || !output.has("manifestYaml")) throw invalid("草稿产物缺失");
                state.set("draft", output); state.put("phase", "WAITING");
                jobs.progress(work.id, "WAIT", "H1", "元智能体已生成第 " + state.path("version").asInt() + " 版草稿，等待需求确认");
            } else {
                if (output.path("number").asInt() <= 0 || blank(output.path("sha").asText()) || blank(output.path("url").asText())) throw invalid("Git 交付缺少 PR 或 commit");
                state.set("delivery", output); state.put("phase", "DELIVERED");
                jobs.progress(work.id, "HUMAN", "BUILD", "骨架 PR 已交付，等待人工开发；尚未评测、部署或发布");
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
        var result = state.deepCopy(); result.remove(List.of("catalog", "feedback", "confirmedVersion")); return result;
    }

    private record Work(String id, ObjectNode state, String action) {}
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static KeelException invalid(String message) { return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message); }
}
