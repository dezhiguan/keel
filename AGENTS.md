# Keel 开发约束

Keel 是智能体平台底座。设计文档在 `docs/` 下按包分类，冲突时以技术文档为准：

- `docs/architecture/Keel-技术文档.html` — 实现细节，最权威（包结构、数据库、接口、流程、开工前核实结论）
- `docs/design/Keel-智能体底座设计.html` — 为什么这样设计、五份契约原文
- `docs/console/Keel-控制台前端.html` — 控制台页面原型

## 动手之前

1. 先读 `docs/specs/p0/` 或 `docs/specs/p1/` 下对应任务的 spec。没有 spec 就先写 spec，不要直接改代码。
2. 涉及跨语言的数据结构，先看 `contracts/`。契约是唯一事实来源。
3. 外部组件（Langfuse、LiteLLM、auth-gateway）的接口**不要凭记忆写**。这三个在本项目里的正确用法和通用写法差别很大，见 `.cursor/rules/external-apis.mdc`。核实不了就停下来问。

## 铁律

### 契约
- 改任何跨语言的数据结构，先改 `contracts/`，再让 Java 模型和 Python pydantic 模型跟着改。反过来不行。
- 契约版本 `keel/v1`。新增字段必须可选；要删字段就升 v2，不允许在 v1 上做破坏性修改。
- SDK 和 starter 必须通过同一套 `contracts/tests`。

### 密钥与模型调用
- 任何代码、配置、测试里都不允许出现厂商 API Key。厂商密钥只存在 LiteLLM 的 Secret 里。
- 调模型只能走 `ctx.llm`（Python）/ `ctx.llm()`（Java）。禁止 import openai、dashscope、anthropic 等厂商 SDK 直连模型。
- 智能体的配置只从 K8s Secret `keel-{agent}` 注入的环境变量读，不要写默认值兜底。

### 审计
- 高风险动作（`risk: high`）必须**同步**写审计，写失败就拒绝执行业务。不允许降级成异步。
- 其余动作走 RocketMQ 异步，按 agent 顺序消息。
- 上报队列有界。队列满时丢追踪，**绝不丢审计**（审计转写本地文件后台重放）。
- `keel_audit` 库的应用账号只有 INSERT / SELECT。代码里不允许出现 UPDATE / DELETE `audit_event` 的语句。
- 审计 payload 只允许 manifest `audit.captureFields` 白名单里的字段，其余一律丢弃。

### 追踪与日志
- span 属性里不放用户原文。原文只按审计白名单进审计。
- 所有日志必须带 `trace_id` 和 `agent`。
- Keel 服务自身的服务级追踪进 SkyWalking，智能体语义追踪进 Langfuse，两者不要混写。

### 错误
- 错误码格式 `{模块}_{原因}`，全部登记在 `contracts/error-codes.yaml`。代码里不允许写字面量错误码。
- 错误体统一 `{code, message, trace_id, retryable}`。
- SSE 出错发 `event: error`，不要直接断连。

### 写代码的方式
- 先写重复代码，同一逻辑出现第三次再抽象。
- 单实现的接口不要建，不允许新增超过一层的继承。
- 不确定的地方留 `TODO` 并写清楚不确定什么，不要猜一个实现糊过去。
- 不要改动 spec 范围之外的文件。
