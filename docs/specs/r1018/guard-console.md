# R1018-guard 护栏页面对齐

## 目标

控制台打开某个智能体的护栏页时，版式、分段开关、试跑结果和网关基线表与 `docs/console/Keel-优化效果-20261018.html` 的「护栏与注入」一致。

## 依赖

- 前置任务：无。检测接口、信任分层和网关基线尚未上线。
- 依赖的契约文件：无。本任务不改 `contracts/`。
- 依赖的外部组件及其真实行为：无。页面不调用 Langfuse、薄网关或智能体。

## 改哪些文件

```
console/src/views/agents/guard.ts
console/src/views/agents/guard.test.ts
console/src/views/agents/AgentGuard.vue
console/src/views/agents/AgentDetail.vue
console/src/views/agents/AgentDebug.vue
console/src/router/index.ts
console/src/layouts/ConsoleLayout.vue
```

## 接口契约

- `GET /api/v1/agents/{name}/guardrails`：模式来自最近一次登记的 `spec.guardrails`，命中和链路来自近 7 天 Langfuse 的一页 `GUARDRAIL` 观测。
- `PUT /api/v1/agents/{name}/guardrails`：把模式写回注册中心的 manifest。正在跑的实例下次部署才读到。
- `GET /api/v1/agents/{name}/guard/check?source=&text=`：按已登记模式做规则判断。不转发到智能体，不调模型，不写记忆和审计。预览身份可以调用。
- 网关基线层没有配置接口，`gatewayAvailable` 为 false。

## 界面对齐

- 原型位置：`docs/console/Keel-优化效果-20261018.html` 的 `#v-guard`（智能体目录 › {agent} › 护栏）
- 对齐清单里的行：无。这是 2026-10-18 优化稿，不在 `docs/specs/r1014/ui-checklist.md`
- 页面数据来自哪个 keel-server 接口：无。紫色「提案」标出尚未接入的检测

入口在智能体详情底部「护栏」，路由 `/agents/{name}/guard`。用户输入可带到调试页的输入框；工具返回和知识库片段留在本页。

## 实现要点

- 智能体层五行、网关基线三行的模式和「处理」文案与优化稿的 `G`、`GW`、`ACT` 一致。
- 「试一下」用优化稿同一条注入规则在浏览器里判断，不进 agent loop。用户输入看第一行模式，工具返回和知识库片段看第二行模式。
- enforce 且来源是用户输入时，结果写 `GUARD_INJECTION_BLOCKED`。enforce 且来源不是用户输入时，结果写剔除并标记 tainted。shadow 只打标。off 写检查已关闭。未命中写正常进入上下文。
- 顶栏面包屑为「智能体目录 / {name} / 护栏」。侧栏仍高亮智能体目录。

## 验收标准

```bash
cd console && npx vitest related src/views/agents/guard.test.ts src/views/agents/AgentGuard.vue
```

- [ ] `/agents/careermate/guard` 含智能体层检查、试一下、网关基线层、调用链路四块
- [ ] 分段开关会改该行的处理标签
- [ ] 默认探针文本检测为拦截；换成「帮我看看这份简历哪里需要改。」为未命中
- [ ] 来源不是用户输入时，「去调试页端到端测」不跳转

## 明确不做

- 不实现 `injection.py`，也不把检测转发到智能体。智能体还没有 `POST /v1/guard/check`。
- 不做污点审批、canary 拦截和网关规则热更新。这些模式可以先记在 manifest 里。
- 不改调试页的 SSE、会话记忆，只接收本页带过去的用户输入
- 不改工具与技能、模型与成本
