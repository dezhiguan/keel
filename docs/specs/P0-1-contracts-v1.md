# P0-1 冻结 keel/v1 五份契约

## 目标

`contracts/` 下六个文件冻结为 keel/v1，之后 Java 模型和 Python 模型都由它们生成。这是整个 P0 的瓶颈任务，冻结前下游七条任务全部阻塞。

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

### invoke.openapi.yaml

四个接口：`POST /v1/invoke`（SSE）、`GET /v1/health`、`GET /v1/manifest`、`POST /v1/feedback`。

请求头：`Authorization`、`traceparent`、`X-Keel-Agent`、`X-Keel-Env`、`X-Keel-Budget`（委派时传剩余预算）、`X-Keel-Eval-Run`（评测流量标记）。

请求体：`{ session_id, input: { text }, context: { user_id, org_id, channel }, options: { stream, eval_run_id } }`

### sse-events.schema.json

五种事件，字段固定：

```
step   { name, status }
tool   { name, status }
token  { text }
final  { answer, citations[], meta, trace_id }
error  { code, message, trace_id, retryable }
```

### audit-event.schema.json

```
event_id, ts, agent, env, trace_id,
actor:    { user_id, org_id, role },
action:   invoke | tool.call | sql.execute | approval | config.change | data.export,
resource, risk: low|mid|high,
decision: allowed | denied | pending | approved | rejected,
approver, payload（白名单字段，已脱敏）, input_digest, prev_hash, hash
```

### trace-attributes.md

`langfuse.*`：`observation.type`（agent / generation / tool / retriever / guardrail / event）、`session.id`、`user.id`、`trace.tags`。

`keel.*`：`agent`、`agent.version`、`parent_agent`、`status`（三档：ok / fallback / failed）、`audit_ids[]`、`fallback_from`、`llm.key_alias`、`llm.request_id`、`tool.deprecated`。

模型调用按 OTel GenAI 约定：`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`。

### error-codes.yaml

格式 `{模块}_{原因}`。起步至少覆盖：`GW_QUOTA_EXCEEDED`、`GW_CONCURRENCY_LIMIT`、`GW_AGENT_OFFLINE`、`TOOL_NOT_GRANTED`、`TOOL_RETIRED`、`TOOL_TIMEOUT`、`APPROVAL_REJECTED`、`APPROVAL_EXPIRED`、`AUDIT_WRITE_FAILED`、`GUARD_INJECTION_BLOCKED`、`DELEGATE_NOT_DECLARED`。

## 实现要点

- **版本策略先写清楚再写字段**：新增字段必须可选，删字段升 v2，平台同时兼容两个小版本。这条决定后面能不能平滑改契约。
- `keel.status` 三档来自 askdb 现有的 `OK_STATUSES` / `SOFT_STATUSES` 口径，迁移时要能一一映射过去，定义前先确认 askdb `trace.py` 里的取值。
- span 属性里**不放用户原文**。契约里就不要给原文留字段，避免以后有人往里塞。
- `audit.captureFields` 是白名单语义，schema 里要写明「未列出的字段一律丢弃」，不是「列出的字段脱敏」。
- error-codes.yaml 每条要带 `retryable` 布尔值，SDK 和网关直接读，不要各自判断。

## 验收标准

```bash
# schema 自身合法
python3 -m jsonschema --check contracts/*.schema.json
npx @redocly/cli lint contracts/invoke.openapi.yaml
```

- [ ] 六个文件全部通过各自的语法校验
- [ ] 用 `Keel-智能体底座设计.html` 第 05 节里的 offshore-wind 完整示例作为 manifest 正例，能通过 schema
- [ ] 构造至少 5 个反例（缺 name、risk=high 但无 approval、未知枚举值等），全部被拒
- [ ] 技术负责人评审通过并打 tag `contracts/v1.0.0`

## 明确不做

- 不做 Service kind 的完整字段（rag-forge 的 `service.yaml` 在 P1-18 再补）
- 不做 Dify 相关字段的细化（P3）
- 不写任何生成代码，那是 P0-2
