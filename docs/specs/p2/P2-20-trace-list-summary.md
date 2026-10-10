# P2-20 链路列表：用户问题、操作人、超时

## 目标

调用记录和原型对齐，并且 Langfuse 读失败时不要显示成没有数据。

- 第一行是用户问题。系统提示词留在生成节点的输入摘要里，输出摘要仍是模型回复。
- 第二行是 `traceId · 操作人`。操作人是控制台用户的显示名，用户表里有角色再拼在后面。
- 列表不拉 observation 全文。上游超时或失败返回 `INSIGHT_UPSTREAM_UNAVAILABLE`，页面保留上一次结果并提示重试。

## 依据

原型 `docs/console/Keel-控制台前端.html` 的调用记录：第一行 `r.q`，第二行 `traceId · user`。`contracts/trace-attributes.md` 约定根 span 的 input 只放用户问题，generation 的 input 是带 role 的消息 JSON。

## 改动

- Python SDK：根 span 写 `input.text` 和 `context.user_id`。generation input 改为消息 JSON，output 仍是回复正文。
- Java starter 的 `/v1/invoke` 同样把这两项写到当前 agent span。
- 控制台调试对话把当前登录用户的显示名放进 `context.user_id`。网关过滤器还是空实现，正式调用等网关落地后沿用同一个字段。
- 列表查询拆成两路：其他 observation 不带 `io`，根 observation 才带 input。读失败不再退回空的本机列表。
- chat-demo 与 echo-agent 共用镜像。SDK 发布时用新的 echo-agent 镜像滚动 chat-demo。

已经写进 Langfuse 的旧 trace 不会改写。新调用起列表才是用户问题和操作人。
