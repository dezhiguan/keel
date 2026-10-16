# 生命周期验收智能体补齐（2026-10-16）

## 范围

仅修复 `qa-lifecycle-1016` 的真实调用、调试会话、证据展示、评测覆盖和独立镜像发布。线上调用仍受现有授权、预算和审计约束。

## 接口契约

- 控制台 `POST /agents/{name}/chat` 请求可携带 `sessionId`，服务端验证 1–128 位安全字符后映射到已有 InvokeRequest 顶层 `session_id`。缺省时保持单轮调用。清空对话生成新会话 ID。
- `ctx.loop(..., prompt=prompt)` 将所取 Langfuse 提示词版本附着到每次模型 generation；自动调用已声明且已授权的只读工具，跨轮使用 `ctx.memory`。
- 智能体详情只在同一 agent 的真实 trace 中看见 `retriever`、带版本的 `generation`、多步 `agent.loop.step` 和循环内工具节点时，才把对应要素标成已验证。Langfuse 不可读时保持待验证。
- 模型网关的短暂 HTTP 5xx 由 SDK 最多重试一次，保留原有预算与审计语义；429 和 4xx 不重试。

## 验收

- 自然语言问值班口径产生 `agent.loop.step`、`shift.policy` 工具调用、最终回复。
- 同一调试会话下一轮能引用上一轮，清空后不再引用；评测调用不写记忆。
- 详情中的要素运行证据来自实际 trace。评测数据集覆盖 RAG、三类工具、拒答、提示词注入和宽泛问题；本地与线上专项测试覆盖会话及网关短暂故障。
- 专属镜像不挂载 ConfigMap 代码；CI 构建并部署后复测线上对话、链路与评测。
