# P3-1 审批中心接入底座

## 目标

控制台「审批中心」去掉对不存在接口的依赖：keel-server 按 `contracts/console-api.openapi.yaml` 提供审批单和人工介入两类待办，批准、驳回、回复都能落库，并遵守超时、冷却和「等待超过 10 分钟不得静默恢复」。

## 依赖

- 前置任务：P0-1a 的表和枚举已在 `V2__platform.sql` 落地；控制台页面与 MSW 已按同一份 OpenAPI 写好。
- 依赖的契约文件：`contracts/console-api.openapi.yaml`、`contracts/error-codes.yaml`、`contracts/invoke.openapi.yaml` 的 resume 请求体。
- 依赖的外部组件及其真实行为：
  - 智能体 `POST /v1/runs/{run_id}/resume` 的请求体以 invoke 契约为准。
  - **待确认**：`/oauth/delegation-token` 的请求体和授权粒度（P0-5 未核实）。本任务不调用这个接口。挂起超过 10 分钟的恢复直接返回 `RUN_RESUME_DENIED`。

## 改哪些文件

```
docs/specs/p3/P3-1-approval-center.md
contracts/console-api.openapi.yaml
keel-server/src/main/resources/db/migration/V5__approval_inbox.sql
keel-server/src/main/java/com/keel/server/approval/**
keel-server/src/main/java/com/keel/server/insight/OverviewService.java
keel-server/src/main/java/com/keel/server/insight/InsightConfiguration.java
keel-server/src/main/java/com/keel/server/integration/agent/AgentEndpointClient.java
keel-server/src/test/java/com/keel/server/approval/**
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
keel-server/src/test/java/com/keel/server/integration/agent/AgentEndpointClientTest.java
```

## 接口契约

已有只读和决策接口保持字段不变。本任务补上发起入口：

```
POST /api/v1/approvals
  body: subjectType, subjectRef, summary, actorUser
        可选 agent, risk, runId, traceId, payloadDigest, policyName, env
  200: Approval

POST /api/v1/runs
  body: runId, agent, reason(input_required|handoff), prompt, actorUser
        可选 env, traceId, deadline, checkpointRef
  200: SuspendedRun
```

`GET /api/v1/approvals`、`POST /api/v1/approvals/{id}/decision`、`GET /api/v1/runs`、`POST /api/v1/runs/{runId}/input` 的形状仍以 OpenAPI 为准。

`approval_request` 补 `risk`、`policy_id`、`expires_at`。`agent_run` 补 `prompt`、`resume_token`。不改 V1–V4。

## 实现要点

- 五类 `subject_type` 共用 `approval_request`。`run_id` 为空表示没有执行在等，批准只改审批单。非空则在单据提交成功后调用该智能体的 `/v1/runs/{id}/resume`，调用失败则整笔回滚，单据仍是 `PENDING`。
- 状态只允许 `PENDING → APPROVED|REJECTED|EXPIRED`。已终态再决策返回 `APPROVAL_EXPIRED`（HTTP 408，以 `error-codes.yaml` 为准）。到期的 `PENDING` 先落成 `EXPIRED` 再返回同一个错误，避免界面把超时当成批准成功。
- 没绑策略时，截止时间是创建后 24 小时。绑了策略则用 `timeout_minutes`。`cooldown_hours > 0` 且同一主体在冷却期内已有 `APPROVED` 时，不再插入新单，返回那张已批准的单。
- 人工介入只列出 `SUSPENDED` 且 `suspend_reason` 为 `input_required` 或 `handoff` 的 run。`approval` 原因留在审批单里。
- 从挂起算起超过 600 秒，恢复返回 `RUN_RESUME_DENIED`，不换票、不调用智能体。委托 token 的请求体尚未核实，禁止猜一个 body 去打 auth-gateway。
- 审批决策和挂起恢复先同步写审计（`AuditStore.append`）。写失败抛 `AUDIT_WRITE_FAILED`，业务更新回滚。审计行只有 INSERT。
- 当前没有登录态。决策人记 `dev`，列表不做审批人过滤。`TODO(P0-5)`：JWT 接入后按 `approval_policy` 过滤，并改用 token 里的用户。
- 总览 `pendingApprovals` 计 `PENDING` 审批单数量。

## 验收标准

```bash
mvn -pl :keel-server -am test -Dtest=ApprovalPolicyEngineTest,ApprovalFlowTest,CostServiceTest,AgentEndpointClientTest
```

- [ ] 批准、驳回、重复决策、超时、冷却、人工回复、超过 10 分钟拒绝恢复，都有用例。
- [ ] 高风险决策的审计写入失败时，审批单状态不变。
- [ ] `GET /api/v1/insight/overview` 的 `pendingApprovals` 等于未决审批单数量。

## 明确不做

- 不实现 ApprovalNotifier（P3-2），不接企业微信或钉钉。
- 不调用 `/oauth/delegation-token`，不改 `AuthGatewayClient`。
- 不改控制台页面。生产构建本来就不走 MSW，接口就绪后现有页面直接可用。
- 不实现「工具」页的真接口（仍属 P3-3 的另一半）。
- 不提供 checkpoint 存储，只保存 `checkpoint_ref` 字符串。
