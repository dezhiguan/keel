# P0-11 Java starter：自动装配与协议端点

## 目标

一个 Spring Boot 应用加上 `keel-spring-boot-starter` 依赖和 `agent.yaml` 后，自动获得 `/v1/invoke`（SSE）、`/v1/health`、`/v1/manifest`、`/v1/feedback`；同一个进程里多个 `@KeelAgent` 各自挂载到 `/{agent}/v1/*` 互不干扰。

## 依赖

- 前置任务：P0-2（Java 契约模型已生成）、P0-4（Maven 骨架）
- 依赖的契约文件：`invoke.openapi.yaml`、`sse-events.schema.json`、`manifest.schema.json`、`error-codes.yaml`
- 依赖的外部组件及其真实行为：这一期不接外部组件，LLM / 知识库 / 审计留桩（追踪在 P0-12）

## 改哪些文件

```
keel-spring-boot-starter/src/main/java/com/keel/starter/
├── autoconfigure/{KeelAutoConfiguration.java,KeelProperties.java}
├── annotation/{KeelAgent.java,KeelEntry.java,KeelTool.java}
├── manifest/{ManifestLoader.java,...}
├── web/{InvokeController.java,HealthController.java,ManifestController.java,FeedbackController.java}
└── context/{KeelContext.java,...}     # 这一期只建接口和桩
keel-spring-boot-starter/src/main/resources/META-INF/spring/*.imports
keel-spring-boot-starter/src/test/java/**
keel-spring-boot-starter/pom.xml
```

现状：上面这些类已作为空占位存在（注解是空 `@interface`，其余是空 class），在原文件上实现。`pom.xml` 目前只依赖 `spring-boot-autoconfigure`，要补 `spring-boot-starter-web`（MVC）和测试依赖；不要引 WebFlux。

## 接口契约

和 Python SDK 完全相同的六个端点（含 P0-1a 的两个）：

```
POST /v1/invoke              # SSE: step/tool/token/final/error/suspend
POST /v1/runs/{run_id}/resume
GET  /v1/runs/{run_id}
GET  /v1/health · /v1/manifest · POST /v1/feedback
```

多智能体时路径前缀为 `/{agent}/v1/invoke`。

## 实现要点

- **starter 走 Spring MVC + 虚拟线程，不是 WebFlux**（`java-service.mdc`）。只有 keel-gateway 用 WebFlux。SSE 用 `SseEmitter`，每个连接占一个虚拟线程。不要因为"SSE 是流式"就去引 WebFlux——混用会把宿主应用的线程模型搞乱，而且 careermate 等存量项目都是 MVC。

- **`SseEmitter` 的超时默认值会咬人**。Spring MVC 的 `SseEmitter` 默认超时来自 `spring.mvc.async.request-timeout`，到点会直接抛 `AsyncRequestTimeoutException` 并断连——这违反「SSE 出错发 `event: error`，不要直接断连」。要显式设置超时（建议 `0L` 表示不超时，由业务和网关控制），并注册 `onTimeout` / `onError` / `onCompletion` 回调做清理。这是 MVC 做 SSE 最常见的坑。

- **一个进程多个 `@KeelAgent` 时，每个 agent 的 manifest、上下文、路径前缀必须隔离**。dev-copilot、prd-agent、test-gen 起步要放在同一个进程（技术文档第 05 节）。不要用单例的静态 context。要有用例：两个 agent 同时挂载，各自 `/v1/manifest` 返回自己的那份。

- **条件装配：有 `agent.yaml` 才启用**。没有 manifest 的普通 Spring 应用引了这个依赖不应该凭空多出 `/v1/invoke`。用 `@ConditionalOnResource` 之类，并有用例验证"没有 agent.yaml 时端点不存在"。

- **挂载不能和宿主已有路由冲突**。careermate 是存量项目，迁移时原有接口要保留。和 Python SDK 的 `mount_to` 一样，冲突要在启动时报错而不是静默覆盖。Spring 对重复映射本来就会报错，但多 agent 前缀拼接时容易产生意外路径，要有用例覆盖。

- **错误码不允许字面量**（铁律）。从 P0-2 生成的 `ErrorCode` 枚举读。全局异常处理把异常映射成 `{code, message, trace_id, retryable}`，`retryable` 直接读枚举属性，不要在这里重新判断。

- **`suspend` 事件发完要 `emitter.complete()`**，不是让连接挂着（P0-1a）。这一期只需发出合规事件，真正的挂起恢复编排在 P3-1。`/v1/runs/{id}/resume` 和 `GET /v1/runs/{id}` 这一期可以返回 `RUN_NOT_FOUND`，但路由和 DTO 要存在，否则 P3-1 要改契约层的东西。

- **配置只从 Secret 注入的环境变量读，不写默认值兜底**（铁律）。`KeelProperties` 不要给 `KEEL_LLM_BASE_URL` 之类设 `@Value` 默认值。

## 验收标准

```bash
mvn -o -pl :keel-spring-boot-starter test
```

- [ ] 一个最小 Spring Boot 应用加依赖 + `agent.yaml` 后，六个端点全部可用
- [ ] `/v1/invoke` 返回的事件逐条通过 `contracts/tests` 的 sse-events 校验
- [ ] 两个 `@KeelAgent` 在同一进程挂载成功，`/{a}/v1/manifest` 和 `/{b}/v1/manifest` 各自正确
- [ ] 没有 `agent.yaml` 时，应用能正常启动且 `/v1/invoke` 返回 404
- [ ] 业务抛异常时收到 `event: error`，**连接没有被直接断开**
- [ ] SSE 连接空闲超过 `spring.mvc.async.request-timeout` 默认值后**不会**被框架断开
- [ ] 客户端断开后，`onCompletion` 被调用且相关资源释放
- [ ] 源码扫描：没有字面量错误码字符串
- [ ] 跑 `contracts/tests` 全套，结论与 Python SDK 一致

## 明确不做

- 不做追踪（P0-12）
- 不做 `ToolInvoker`、`Delegator`、`ClientAssertionSigner`、`JwtVerifier`、`guard/`、`AuditReporter` 的实现，只建接口和桩
- 不做 `HeartbeatReporter`（P1-10）
- 不做 `/v1/runs/{id}/resume` 的真实恢复逻辑（P3-1）
