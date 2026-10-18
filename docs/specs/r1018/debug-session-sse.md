# R1018 调试会话与 SSE

## 目标

控制台调试对话从 keel-server 读取自己的会话，调用时把智能体的 SSE 原样转到页面。

## 依赖

- 前置任务：P2-18 调试页。智能体 `/v1/invoke` 已经返回 `step` / `tool` / `token` / `final` / `error`。
- 依赖的契约文件：`contracts/console-api.openapi.yaml`。
- 外部组件：无。调试仍只调 keel-server。

## 改哪些文件

```
docs/specs/r1018/debug-session-sse.md
contracts/console-api.openapi.yaml
keel-server/src/main/resources/db/migration/V28__debug_session.sql
keel-server/src/main/java/com/keel/server/registry/service/DebugSessionService.java
keel-server/src/main/java/com/keel/server/registry/service/DebugSseRelay.java
keel-server/src/main/java/com/keel/server/registry/service/AgentChatService.java
keel-server/src/main/java/com/keel/server/registry/controller/AgentController.java
keel-server/src/main/java/com/keel/server/integration/agent/AgentEndpointClient.java
keel-server/src/test/java/com/keel/server/registry/service/DebugSseRelayTest.java
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
console/src/api/agents.ts
console/src/views/agents/debug.ts
console/src/views/agents/debug.test.ts
console/src/views/agents/AgentDebug.vue
```

## 接口契约

- `GET /agents/{name}/sessions?cursor=`：当前登录人、该智能体、该智能体环境下的会话，按更新时间倒序，每页 10 个。
- `POST /agents/{name}/sessions`：新建空会话。
- `DELETE /agents/{name}/sessions/{id}`：只删自己的。
- `GET /agents/{name}/sessions/{id}/messages?before=`：每页 10 条，`before` 为空取最新一页。
- `POST /agents/{name}/chat/stream`：`text/event-stream`。收到 `final` 后把这一轮用户消息和回复写入 `session_message`，保留 7 天。客户端断开或没有 `final` 时不写。请求头带 `X-Keel-Eval-Run` 时不写。

会话编号仍按「登录人 + 浏览器会话号」哈希后传给智能体，避免串到别人的 Pod 本地记忆。

## 界面对齐

- 原型：`docs/console/Keel-优化效果-20261018.html` 的 `#v-debug`。
- 页面从上面的接口读会话和消息；右侧事件来自 SSE，不另存。

## 明确不做

- 不把智能体 Pod 里的 keel-lite 会话搬到 keel-server。托管页仍不读控制台会话。
- 不实现护栏检测。流里如果出现 `guardrail` 事件，页面照样显示。
