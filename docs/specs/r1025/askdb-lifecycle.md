# AskDB 生命周期接入

补齐 P1-17：AskDB 使用 SDK 的 invoke、提示词、模型网关、审计、追踪和评测，保留自己的 SQL 安全与业务权限。现有调试窗口和提示词、评测、链路、审计页面不新增交互。对照 docs/console/Keel-控制台前端-20261014.html 和 r1014/ui-checklist.md，验收包括实际页面使用的调试 SSE 与现有治理接口。

范围：AgentChatService 对 askdb 透传已认证的 username（非 displayName），并由统一内部凭据提供 askdb、meta-agent、careermate 的桥接令牌；自检、候选同步、护栏探针使用同一凭据。SDK 评测 Job 仅为 askdb 注入自身令牌，不能借用 meta-agent 的权限。Secret 从环境读取，不在代码配置中写密钥。现有契约保持兼容。generation 的结构化输出记录实际工具参数，解析失败也保留模型输出证据。SDK 内部 ChatResult 可选用量字段保留结构化调用的实际 token，用于 AskDB 业务步骤预算；SDK 评测请求优先使用 KEEL_BRIDGE_TOKEN，兼容旧 meta-agent 变量。

验收：鉴权缺失拒绝，askdb 用户与环境固定；普通/流式调试和实际注册/提示词/评测/追踪/审计读取；回归现有对应 Java 测试。影子历史不删，未完成的线上项明确保留。
