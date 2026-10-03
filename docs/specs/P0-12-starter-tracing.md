# P0-12 Java starter 追踪

## 目标

把 careermate 现有的 `TracingMdcFilter` / `AgentTracing` 迁进 starter，加 OTLP/HTTP 导出到 Langfuse。careermate 现有测试仍然通过。

## 依赖

- 前置任务：P0-11（starter 骨架）
- 依赖的契约文件：`contracts/trace-attributes.md`
- 依赖的外部组件及其真实行为：Langfuse v4，见 `.cursor/rules/external-apis.mdc`

## 改哪些文件

```
keel-spring-boot-starter/src/main/java/com/keel/starter/tracing/**
keel-spring-boot-starter/src/test/java/com/keel/starter/tracing/**
```

careermate 仓库的删除动作在 P2-13，本任务**不碰 careermate**，只把逻辑搬进 starter 并保证行为等价。

## 接口契约

属性约定与 Python SDK 完全相同（`contracts/trace-attributes.md`），两边不允许有差异。模型调用按 OTel GenAI 约定：`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`。

## 实现要点

- **不允许 `OtlpGrpcSpanExporter`**。Langfuse v4 只支持 OTLP/HTTP。Java 生态里 gRPC exporter 是更常见的选择，自动补全很容易选中它，而且连得上才怪——这个错误表现为"上报没反应"，排查成本高。加一条源码扫描测试钉死。

- **必须带请求头 `x-langfuse-ingestion-version: 4`**，漏了数据 15 分钟后才可见且不报错。用假的 Langfuse HTTP 服务断言请求头真的发出去了，不是断言配置里写了。

- **先确认 careermate 的现有行为，再决定搬什么**。`TracingMdcFilter` 和 `AgentTracing` 是现成的、在生产跑着的代码。搬之前要读懂它现在往 MDC 里放什么、span 怎么命名、有没有业务依赖这些字段。**不要照着文档重写一遍**——重写出来的东西和现有行为有差异，P2-13 迁移时才会发现。这是本任务最大的风险点。

- **已确认（2026-10-03，读 careermate `com.careermate.observability`）**：运行时是 **micrometer-tracing-bridge-otel**，不是业务代码直接用 OTel API。`AgentTracing`、`LlmTracingSupport`、`TraceHeaderPropagator` 都注入 `io.micrometer.tracing.Tracer`。`TraceIdResolver` 先读 SkyWalking `TraceContext.traceId()`（忽略 `N/A` 和 `Ignored_Trace`），没有再用 Micrometer 当前 span。MDC 键是 `traceId`、`requestId`、`sessionId`、`userId`、`spanId`、`service`；响应头是 `X-Trace-Id`、`X-Request-Id`；会话头是 `X-CareerMate-Session-Id`。流式请求（`Accept: text/event-stream` 或路径以 `/messages/stream` 结尾）不套 `ContentCachingResponseWrapper`。span 名是调用方传入的，流式入口固定 `agent.stream`，模型调用固定 `llm.chat`。SkyWalking 仍由 careermate 自己的 agent 负责，starter **不引入** SkyWalking 依赖；Langfuse 导出用 OTel HTTP exporter 直接发，避免 Micrometer 默认走到 gRPC。

- **MDC 里必须有 `trace_id` 和 `agent`**（铁律：所有日志必须带这两个）。虚拟线程下 MDC 的传播行为和平台线程不同——`InheritableThreadLocal` 在虚拟线程上不工作。starter 走 MVC + 虚拟线程（P0-11），这一条必须有用例：虚拟线程里打的日志带不带 trace_id。

- **SSE 流式响应期间的 span 生命周期**。`SseEmitter` 异步返回后，请求线程就结束了，但 span 要等到流真正结束才能 end。不要在 filter 的 `finally` 里 end span，否则所有 trace 的耗时都是几毫秒。要挂在 `SseEmitter` 的 `onCompletion` / `onError` 上。

- **span 属性不放用户原文**（铁律）。用例构造一句特征串，断言不出现在上报体里。

- **Keel 服务自身的追踪进 SkyWalking，不要混写**。starter 是给智能体用的，只管 Langfuse 这条线。如果 careermate 现在同时接了 SkyWalking，保持原样不动。

## 验收标准

```bash
mvn -o -pl :keel-spring-boot-starter test
```

- [ ] 假 Langfuse 服务收到 OTLP/HTTP 请求，带 `x-langfuse-ingestion-version: 4`
- [ ] 源码扫描：没有 `OtlpGrpcSpanExporter`
- [ ] 节点树层级与类型正确（agent / generation / tool / retriever）
- [ ] 虚拟线程里打出的日志带 `trace_id` 和 `agent`
- [ ] 一次 SSE 调用的 span 耗时等于流的真实时长，不是请求线程返回的时刻
- [ ] span 属性里不含用户输入原文
- [ ] 同一组输入下，starter 和 Python SDK 产生的属性键集合一致（对着 `trace-attributes.md` 逐项核）
- [ ] careermate 现有测试全部通过（在 careermate 仓库跑，本任务只验证不改）

## 明确不做

- 不删 careermate 仓库里的任何文件（P2-13）
- 不做本地缓冲重放。Python 侧 P0-7 做了，Java 侧这一期先不做——starter 跑在 K8s 里，网络抖动的处置方式和本地开发不同，等 P1 有真实数据再定
- 不做采样策略
- 不接 SkyWalking
