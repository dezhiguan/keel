# R1-4 本地会话记忆

## 目标

`ctx.memory` 在 keel-lite 的本地 SQLite 中按 agent 与 session 保存有 TTL 的会话消息；`ctx.loop` 自动跨轮读取与写入，评测流量拒绝写入。

## 依赖

- 前置任务：R1-3。
- 依赖的契约文件：`contracts/invoke.openapi.yaml` 已有 `session_id` 和评测请求的 `X-Keel-Eval-Run`；`contracts/console-api.openapi.yaml` 的 `AgentDetail` 新增可选记忆状态对象。
- 依赖的外部组件及其真实行为：无新增外部接口；仅使用 keel-lite 既有 SQLite 文件。

## 改哪些文件

```
docs/specs/r1014/R1-4-session-memory.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
sdk-python/keel/lite/store.py
sdk-python/keel/lite/memory.py
sdk-python/keel/context.py
sdk-python/keel/asgi.py
sdk-python/tests/lite/test_memory.py
sdk-python/tests/loop/test_loop.py
keel-server/src/main/java/com/keel/server/registry/model/dto/AgentDetail.java
keel-server/src/test/java/com/keel/server/registry/model/AgentDetailTest.java
console/src/views/agents/AgentDetail.vue
console/src/views/agents/agentDrawer.ts
console/src/views/agents/agentDrawer.test.ts
```

## 接口契约

- `Context.memory.history() -> list[dict]`：按时间返回未过期的 `{role, content}`，仅当前 agent 和 session。
- `Context.memory.append(role, content) -> None`：写入一条消息，默认 TTL 7 天；无 session 或 `eval_run_id` 非空时拒绝写入。
- `ctx.loop` 在每轮开始时合并本 session 既有历史，正常完成后保存本轮用户消息和模型最终回复；评测轮次不写入。
- `AgentDetail.memory` 为可选对象，包含 `session`、`user`、`task`、`experience` 四层状态；会话状态明确标为“本地开发可用”，其他层按真实接入情况显示“未接入”。

## 界面对齐

- 原型位置：`#/agents` → 详情抽屉 → “记忆” tab。
- 清单对应行：`智能体目录 #/agents → 详情抽屉 / 记忆 tab`。R1-4 完成本地会话层与四层状态展示；R4-7 的用户记忆管理仍标未开始。
- 页面数据：`GET /api/v1/agents/{name}` 的 `memory`；不使用原型中的固定条数。

## 实现要点

- SQLite 按 agent、session 双键隔离；查询过滤 TTL，避免旧消息进入模型上下文。
- checkpoint 恢复时使用保存的消息历史，不能再叠加一次会话记忆或重复写入。
- 本地会话原文不进审计 payload 或日志；评测写入失败需要显式反馈。

## 验收标准

```bash
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/lite tests/loop tests/test_runtime.py -q
mvn -o -pl :keel-server test
cd console && npx vitest related src/views/agents/AgentDetail.vue src/views/agents/agentDrawer.ts --run
```

- [x] 同 session 第二轮能引用第一轮；不同 agent/session 隔离，过期消息不可读。
- [x] `eval_run_id` 非空时 `ctx.memory.append` 被拒，`ctx.loop` 不写入。
- [x] 控制台四层状态来自接口，R4-7 未实现的用户记忆管理不显示可操作入口。

## 明确不做

- 不实现生产环境 keel-memory、用户记忆的查删与合规策略，归 R4-7。
