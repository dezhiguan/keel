# P0-1 冻结 keel/v1 五份契约

## 目标

`contracts/` 下六个文件冻结为 keel/v1，之后 Java 模型和 Python 模型都由它们生成。这是整个 P0 的瓶颈任务，冻结前下游七条任务全部阻塞。

运行生命周期（`run_id`、`suspend` 事件、`resume` 接口）的字段定义拆在 `P0-1a-run-lifecycle.md`，两份一起评审、一起冻结。本文件里标注「见 P0-1a」的地方以那份为准。

## 依赖

- 前置任务：无，第一天开工
- 依赖的外部组件行为：
  - Langfuse v4 的 OTel 属性映射（`trace-attributes.md` 要和它对齐）
  - auth-gateway 的 JWT claim 结构（`roles` claim 尚未存在，见 P0-5）

## 改哪些文件

```
contracts/manifest.schema.json
contracts/invoke.openapi.yaml
contracts/sse-events.schema.json
contracts/audit-event.schema.json
contracts/trace-attributes.md
contracts/error-codes.yaml
```

## 接口契约

### manifest.schema.json

`apiVersion: keel/v1`、`kind: Agent | Service`。除 `metadata.name` 和 `spec.runtime.endpoint` 外所有字段必须有默认值。

顶层结构：

```
metadata: { name, displayName, owner }
spec:
  runtime:    { type: code|dify, language, endpoint, liveness: k8s|heartbeat|probe }
  auth:       { audience, roles[] }
  models:     { default, fallback[], budget: { dailyCny, maxSteps } }
  knowledge:  [ { kb } ]
  tools:      [ { name, access: read|write|exec, risk: low|mid|high, approval } ]
  delegates:  [ agentName ]
  guardrails: { inputInjection, grounding: off|shadow|enforce, pii }
  audit:      { retentionDays, captureFields[] }
  prompts:    { source: langfuse, label }
  eval:       { dataset, evaluators[], gate: { minScore, maxRegression, byTag } }
  quality:    { onlineEvalSampling, feedback, annotationQueue }
```

`kind: Service`（rag-forge 这类共享服务）**这次必须有 `models` 和 `budget` 两段**，字段形状和 Agent 一致。共享服务也要走 LiteLLM 发虚拟 Key，没有这两段 `keel register` 开通不了资源。其余段（`eval`、`quality`、`delegates`、`prompts`）对 Service 不适用，schema 里按 `kind` 条件化。

Service 的 `models.fallback` 要能按用途分开写，不能全局一个降级列表：

```
models:
  byPurpose:
    rewrite:   { default: qwen-flash,     fallback: [qwen-plus] }
    judge:     { default: qwen-plus,      fallback: [deepseek-v3] }
    embedding: { default: text-embedding-v3, fallback: [] }   # 必须为空，见实现要点
  budget: { dailyCny }
```

### invoke.openapi.yaml

六个接口：`POST /v1/invoke`（SSE）、`GET /v1/health`、`GET /v1/manifest`、`POST /v1/feedback`，加上 `POST /v1/runs/{run_id}/resume`、`GET /v1/runs/{run_id}`（见 P0-1a）。

请求头：`Authorization`、`traceparent`、`X-Keel-Agent`、`X-Keel-Env`、`X-Keel-Budget`（委派时传剩余预算）、`X-Keel-Eval-Run`（评测流量标记）。

请求体：`{ session_id, idempotency_key, input: { text }, context: { user_id, org_id, channel }, options: { stream, eval_run_id } }`

### sse-events.schema.json

六种事件，字段固定：

```
step    { name, status }
tool    { name, status }
token   { text }
final   { answer, citations[], meta, trace_id, run_id }
error   { code, message, trace_id, retryable, run_id }
suspend { run_id, reason, ref, prompt, deadline, resume_token, trace_id }   # 见 P0-1a
```

### audit-event.schema.json

```
event_id, ts, agent, env, trace_id,
actor:    { user_id, org_id, role },
action:   invoke | tool.call | sql.execute | approval | config.change | data.export
          | run.suspend | run.resume | agent.register | release.gate,
resource, risk: low|mid|high,
decision: allowed | denied | pending | approved | rejected,
approver, payload（白名单字段，已脱敏）, input_digest, prev_hash, hash
```

### trace-attributes.md

`langfuse.*`：`observation.type`（agent / generation / tool / retriever / guardrail / event）、`session.id`、`user.id`、`trace.tags`。

`keel.*`：`agent`、`agent.version`、`parent_agent`、`status`（三档：ok / fallback / failed）、`audit_ids[]`、`fallback_from`、`llm.key_alias`、`llm.request_id`、`tool.deprecated`。

模型调用按 OTel GenAI 约定：`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`。

### error-codes.yaml

格式 `{模块}_{原因}`。起步至少覆盖：`GW_QUOTA_EXCEEDED`、`GW_CONCURRENCY_LIMIT`、`GW_AGENT_OFFLINE`、`TOOL_NOT_GRANTED`、`TOOL_RETIRED`、`TOOL_TIMEOUT`、`APPROVAL_REJECTED`、`APPROVAL_EXPIRED`、`AUDIT_WRITE_FAILED`、`GUARD_INJECTION_BLOCKED`、`DELEGATE_NOT_DECLARED`，以及 P0-1a 的 `RUN_NOT_FOUND`、`RUN_NOT_RESUMABLE`、`RUN_EXPIRED`、`RUN_RESUME_DENIED`。

P1-0 / P1-15a 已追加（随本次一起评审）：`SERVER_INVALID_PARAM`、`SERVER_NOT_FOUND`、`SERVER_INTERNAL_ERROR`、`TOOL_HAS_PROD_DEPENDENTS`。

## 当前状态（2026-10-03）

六个文件已起草，`bash scripts/validate-contracts.sh` 全部通过，**尚未评审、未打 tag**。冻结前还要过一遍：

- [x] `audit-event.schema.json` 的 `action` 与 `console-api.openapi.yaml` 的 `AuditAction` 统一为同一组十个值（原先两边各缺两个）
- [ ] 评审 P1-0 / P1-15a 追加的四个错误码
- [ ] 评审 `console-api.openapi.yaml` 里 `Approval.subjectType` 等字段和 `/runs` 接口（草案，控制台契约，不属于 keel/v1，但取值必须与 P0-1a 的 `subject_type` / `suspend.reason` 一致）
- [ ] 打 tag `contracts/v1.0.0`，之后 P0-2 / P0-3 / P0-6 / P0-9 / P0-11 才能开工

## 实现要点

- **版本策略先写清楚再写字段**：新增字段必须可选，删字段升 v2，平台同时兼容两个小版本。这条决定后面能不能平滑改契约。
- **枚举值不适用「新增字段可选」那条规则**：旧消费方收到没见过的枚举值不知道怎么办，所以加枚举值是破坏性变更。这次要把以后会用到的取值一次写全（`suspend.reason` 三个、`action` 两个新值），实现可以只做一部分。
- `keel.status` 三档来自 askdb 现有的 `OK_STATUSES` / `SOFT_STATUSES` 口径，迁移时要能一一映射过去，定义前先确认 askdb `trace.py` 里的取值。
- span 属性里**不放用户原文**。契约里就不要给原文留字段，避免以后有人往里塞。
- `audit.captureFields` 是白名单语义，schema 里要写明「未列出的字段一律丢弃」，不是「列出的字段脱敏」。
- **向量化模型不允许配 fallback**，schema 里就要把 `models.byPurpose.embedding.fallback` 限制成空数组。向量化一旦降级切到别的模型，新算的向量和库里的存量向量不在同一个空间，检索会**静默变烂**——不报错，只是召回率掉下去。改写和 judge 可以降级，向量化不行。
- error-codes.yaml 每条要带 `retryable` 布尔值，SDK 和网关直接读，不要各自判断。

## 验收标准

```bash
# schema 自身合法
python3 -m jsonschema --check contracts/*.schema.json
npx @redocly/cli lint contracts/invoke.openapi.yaml
```

- [ ] 六个文件全部通过各自的语法校验
- [ ] 用 `docs/design/Keel-智能体底座设计.html` 第 05 节里的 offshore-wind 完整示例作为 manifest 正例，能通过 schema
- [ ] 一份 `kind: Service` 的 rag-forge 正例（含 `models.byPurpose` 三种用途和 `budget`）能通过 schema
- [ ] `embedding.fallback` 非空时被拒
- [ ] 构造至少 5 个反例（缺 name、risk=high 但无 approval、未知枚举值等），全部被拒
- [ ] 技术负责人评审通过并打 tag `contracts/v1.0.0`

## 明确不做

- 不做 Service kind 的**全部**字段，但 `models`（含 `byPurpose`）和 `budget` 这次必须定。rag-forge 的模型调用要收进 LiteLLM，没这两段发不出虚拟 Key，拖到 P1-18 就晚了。健康探测、知识库元数据等 Service 专有字段仍留到 P1-18
- 不做 Dify 相关字段的细化（P3）
- 不写任何生成代码，那是 P0-2
