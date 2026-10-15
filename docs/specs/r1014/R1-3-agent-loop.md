# R1-3 SDK 工具调用循环

## 目标

Python SDK 的 `ctx.loop` 执行模型选工具、工具结果回传和再次决策；支持步数、预算、重复调用限制，并在高风险工具审批后从 checkpoint 继续。

## 依赖

- 前置任务：R1-1、R1-2。
- 依赖的契约文件：`contracts/error-codes.yaml` 的 `AGENT_MAX_STEPS_EXCEEDED`、`contracts/trace-attributes.md` 的 `keel.loop.step`、`contracts/console-api.openapi.yaml` 的 `AgentDetail`。
- 依赖的外部组件及其真实行为：薄网关 `POST /v1/chat/completions` 经现有客户端调用；高风险审批沿用 SDK 已有 `POST /api/v1/approvals` 和 `GET /api/v1/approvals` 路径，不新增外部接口。

## 改哪些文件

```
docs/specs/r1014/R1-3-agent-loop.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
sdk-python/keel/context.py
sdk-python/tests/loop/test_loop.py
sdk-python/tests/test_runtime.py
keel-server/src/main/java/com/keel/server/registry/model/dto/AgentDetail.java
keel-server/src/test/java/com/keel/server/registry/model/AgentDetailTest.java
console/src/views/agents/AgentDetail.vue
console/src/views/agents/agentDrawer.ts
console/src/views/agents/agentDrawer.test.ts
```

## 接口契约

```python
async def ctx.loop(messages: list[dict], tools: list[dict], *, max_steps: int | None = None) -> FinalEvent | SuspendEvent: ...
```

- 每个模型决策为一步，从 1 开始，span 属性 `keel.loop.step`。完成时返回 `FinalEvent`；高风险工具审批时返回 `SuspendEvent`。
- `max_steps` 和调用预算 `budget.steps` 取更小值；超出报 `AGENT_MAX_STEPS_EXCEEDED`，由 ASGI 发 `event: error`。预算人民币非正时沿用 `GW_QUOTA_EXCEEDED`。
- 同一工具同一参数连续两次视为原地打转，拒绝继续执行；只执行 manifest 声明的工具。
- `AgentDetail.execution` 可选对象：`current`、`target`、`constraints`，来自服务端保存的 manifest 和当前 SDK 能力配置。旧记录缺字段时控制台显示“未声明”。

## 界面对齐

- 原型位置：`#/agents` → 详情抽屉 → 概览 → “运行方式”（现在 / 目标 / 约束）。
- 清单对应行：`智能体目录 #/agents → 详情抽屉 / 概览 · 运行方式`。
- 页面数据：`GET /api/v1/agents/{name}` 的 `execution`；不展示原型写死值。要素检查 12 项由 R1-8 完成。

## 实现要点

- checkpoint 保存消息、已用步数、待执行工具及其后续工具；先写 checkpoint 再返回 suspend。恢复后执行获批工具，随后清除仅本次的 `resuming` 标志，下一次高风险动作仍需审批。
- 已有普通代码调用 `ctx.tools.call` 和 `ctx.llm.chat` 的行为不变。工具结果以 `role: tool` 和原 tool_call id 回传模型。
- checkpoint 文件仅包含模型上下文和工具参数，按 run id 存于本地受控目录；不写日志或审计 payload。

## 验收标准

```bash
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/loop tests/test_runtime.py -q
mvn -o -pl :keel-server test
cd console && npx vitest related src/views/agents/AgentDetail.vue src/views/agents/agentDrawer.ts --run
```

- [x] 假薄网关集成测试覆盖完成、步数上限、预算不足、连续重复工具。
- [x] 高风险工具挂起后从 checkpoint 恢复，同一 run 不重放此前工具；恢复后第二个高风险工具仍须审批。
- [x] 每步有 `keel.loop.step`，超步数经 ASGI 发 `event: error`。
- [x] 控制台运行方式三行来自 `AgentDetail` 接口，清单状态已更新。

## 明确不做

- 不实现长期记忆或 LangGraph 模板，归 R1-4、R1-5。
- 不更改审批服务对外接口。
