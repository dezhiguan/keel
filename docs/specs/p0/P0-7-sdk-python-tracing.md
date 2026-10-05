# P0-7 sdk-python 追踪

## 目标

Langfuse dev 项目里能看到 hello-agent 的完整节点树；断网期间的 span 落到本地 jsonl，恢复后重放上去不丢。

## 依赖

- 前置任务：P0-6（SDK 骨架，埋点位置已定）
- 依赖的契约文件：`contracts/trace-attributes.md`
- 依赖的外部组件及其真实行为：Langfuse v4，见 `.cursor/rules/external-apis.mdc`。要点摘在下面

## 改哪些文件

```
sdk-python/keel/tracing/{__init__.py,otel.py,attrs.py,buffer.py,langgraph.py}
sdk-python/keel/context.py        # 把 P0-6 留的追踪桩换成实现
sdk-python/tests/tracing/**
```

## 接口契约

上报方式（**错一个都会静默出问题**）：

- 端点只有 `POST /api/public/otel/v1/traces`，Basic Auth（公钥:私钥）
- **必须带请求头 `x-langfuse-ingestion-version: 4`**
- **只支持 OTLP/HTTP，不支持 gRPC**

属性约定：

```
langfuse.observation.type   agent | generation | tool | retriever | guardrail | event
langfuse.session.id · langfuse.user.id · langfuse.trace.tags
keel.agent · keel.agent.version · keel.parent_agent · keel.status · keel.audit_ids[]
keel.fallback_from · keel.llm.key_alias · keel.llm.request_id · keel.tool.deprecated
gen_ai.request.model · gen_ai.usage.input_tokens · gen_ai.usage.output_tokens
```

## 实现要点

- **漏掉 `x-langfuse-ingestion-version: 4` 不会报错**，数据大约 15 分钟后才可见。开发时很容易误判成"上报失败"去改别的地方。请求头要写成常量并有用例断言它出现在实际请求里——用假的 Langfuse HTTP 服务断言请求头，不是断言配置里写了。

- **不允许 import langfuse**（`python-sdk.mdc`）。`keel/tracing/`、`agent.py`、`context.py` 里运行时上报只走标准 OTel。Langfuse SDK 只能出现在 `keel/eval/` 和 `keel/gate/`。这一期这两个目录还不存在，所以整个 SDK 里不应该有任何 langfuse import——加一条测试扫源码钉死。

- **span 属性里不放用户原文**（铁律）。原文只按审计白名单进审计。`attrs.py` 里就不要给原文留常量；`ctx.step()` 的参数也不要接受自由文本当属性。

- **`keel.status` 只有三档**：`ok` / `fallback` / `failed`。它来自 askdb 现有的 `OK_STATUSES` / `SOFT_STATUSES` 口径，P1-17 迁移时要一一映射过去。定义前先确认 askdb `trace.py` 里的实际取值，**不要凭文档猜**——映射错了 askdb 迁移后的质量指标会整体漂移。

- **缓冲重放的边界要有界**（`python-sdk.mdc`）。本地 jsonl 要有大小上限和条数上限，超了丢最旧的追踪。追踪可以丢，审计不能丢——但审计的重放在 P0-8，这里只做追踪。两者**不要共用一个缓冲实现**，丢弃策略根本不同，共用迟早会把审计也丢掉。

- **重放不能造成重复**。进程崩溃时可能「已发送但未标记」，重放会重复上报。OTel 的 span id 是幂等键，Langfuse 侧对同一 span id 的重复写入行为**待确认**——实测一下重复上报同一个 span 会变成两条还是覆盖，结论写回这里。做不到幂等就在重放时记录水位线。

- **LangGraph 回调适配是 askdb 的迁移前提**（P1-17）。`langgraph.py` 要把 LangGraph 的节点映射成 `agent` / `tool` / `retriever` 类型的 span。askdb 内部的子智能体（router、verifier、synthesizer）**不单独注册**，作为 askdb 内部的 `agent` 类型 span 出现。

- 日志必须带 `trace_id` 和 `agent`（铁律）。这一期顺手把日志的 MDC 等价物配好。

## 验收标准

```bash
cd sdk-python && pytest tests/tracing -q
```

- [ ] hello-agent 跑一次，Langfuse dev 项目里能看到完整节点树，层级和类型正确
- [ ] 用假的 Langfuse HTTP 服务断言请求里带 `x-langfuse-ingestion-version: 4`
- [ ] 断网（假服务返回连接错误）期间产生的 span 落到本地 jsonl；恢复后自动重放且 Langfuse 收到
- [ ] 缓冲超过上限时丢弃最旧的追踪，且打印一条 WARNING，进程不崩
- [ ] 源码扫描：`keel/` 下除 `eval/`、`gate/` 外没有 `import langfuse`
- [ ] 源码扫描：没有 `OtlpGrpcSpanExporter`
- [ ] generation 的 input / output 带这次调用的问题和回复；其他 span 的属性里不包含用户输入原文
- [ ] 所有日志行都带 `trace_id` 和 `agent`

## 明确不做

- 不做评测和门禁（`eval/`、`gate/` 在 P2-1、P2-4）
- 不做审计上报（P0-8）
- 不做 SkyWalking 接入。Keel 服务自身的服务级追踪进 SkyWalking，智能体语义追踪进 Langfuse，两者不混写；SDK 这边只管后者
- 不做采样策略调优
