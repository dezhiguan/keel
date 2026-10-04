# 追踪属性约定 · keel/v1

Python SDK 和 Java starter 产生的属性键集合必须完全一致。这份文件是两边的唯一依据。

## 上报方式

- 端点只有 `POST /api/public/otel/v1/traces`，Basic Auth（公钥:私钥）
- **必须带请求头 `x-langfuse-ingestion-version: 4`**。漏了数据要 15 分钟后才可见，且不报错
- **只支持 OTLP/HTTP，不支持 gRPC。** 不允许使用 `OtlpGrpcSpanExporter`
- Keel 服务自身的服务级追踪进 SkyWalking，智能体语义追踪进 Langfuse，两者不混写

## langfuse.*

| 属性 | 取值 | 说明 |
|---|---|---|
| `langfuse.observation.type` | `agent` \| `generation` \| `tool` \| `retriever` \| `guardrail` \| `event` | 节点类型 |
| `langfuse.session.id` | 字符串 | 多轮对话归到同一会话 |
| `langfuse.user.id` | 字符串 | |
| `langfuse.trace.tags` | 字符串数组 | |

在线评估器配成 **observation 级**（挂在根 agent 节点上）。v4 不再运行 trace 级评估器。

## keel.*

| 属性 | 取值 | 说明 |
|---|---|---|
| `keel.agent` | 字符串 | |
| `keel.agent.version` | 字符串 | |
| `keel.parent_agent` | 字符串 | 被委派时填上游智能体名 |
| `keel.status` | `ok` \| `fallback` \| `failed` | **只有三档**，和 SSE 事件同一套口径 |
| `keel.audit_ids` | 字符串数组 | 本节点写出的审计事件 id，控制台据此在追踪和审计之间互跳 |
| `keel.fallback_from` | 字符串 | 降级前的模型别名 |
| `keel.llm.key_alias` | 字符串 | 薄网关虚拟 Key 别名 `{agent}-{env}`，成本按它归集 |
| `keel.llm.request_id` | 字符串 | 和薄网关花费明细对账用 |
| `keel.tool.deprecated` | 布尔 | 调用了废弃期内的工具 |
| `keel.run.id` | 字符串 | 一次执行的 id |
| `keel.run.suspended` | 布尔 | 本次执行中途挂起过 |
| `keel.run.wait_ms` | 整数 | 人工等待时长。**不计入智能体耗时**，控制台按它算「人工等待 4m12s」 |

`keel.status` 的三档来自 askdb 现有的 `OK_STATUSES` / `SOFT_STATUSES` 口径。P1-17 迁移时要一一映射过去，**映射前先确认 askdb `trace.py` 里的实际取值**，映射错了质量指标会整体漂移。

## 模型调用

按 OTel GenAI 语义约定，不要自造：

```
gen_ai.request.model
gen_ai.usage.input_tokens
gen_ai.usage.output_tokens
```

generation span **只由 Keel SDK 上报一次**。薄网关不向 Langfuse 上报，否则一次调用会记两条。

## 禁止事项

- **span 属性里不放用户原文。** 原文只按 manifest `audit.captureFields` 白名单进审计。契约里不给原文留字段，避免有人往里塞
- 不放厂商 API Key、不放完整 prompt、不放检索到的文档全文（只放 citations 的标识）
- 所有日志必须带 `trace_id` 和 `agent`
