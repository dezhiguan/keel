# R1022 上下文容量卡点

## 目标

`ctx.llm.chat` 在请求薄网关之前按假定容量估算上下文。超过 10000 token 时拒绝本次调用，并通过现有 `event: error` 提示「上下文窗口过大」。

## 依赖

- 前置任务：R1-2。
- 依赖的契约文件：`contracts/error-codes.yaml` 新增 `AGENT_CONTEXT_EXCEEDED`。
- 依赖的外部组件及其真实行为：不调用薄网关。估算在本地完成。

## 改哪些文件

```
docs/specs/r1022/context-token-budget.md
contracts/error-codes.yaml
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
sdk-python/keel/_generated/errors.py
sdk-python/keel/llm/client.py
sdk-python/tests/llm/test_context_budget.py
```

## 接口契约

错误码 `AGENT_CONTEXT_EXCEEDED`：消息「上下文窗口过大」，`retryable: false`，HTTP 409。SSE 仍是 `event: error`，体为 `{code, message, trace_id, retryable, run_id}`。

容量来自环境变量 `KEEL_CONTEXT_TOKEN_BUDGET`。未设置或不是非负整数时按 10000。设为 `0` 时不检查。

估算：ASCII 4 个字符为 1 token，其余字符 1 个字符为 1 token；每条消息另加 4 token。计入即将发送的消息和工具定义。估算大于容量则拒绝，等于容量仍发送。

## 界面对齐

无。调试页已把 `event: error` 的 `message` 显示为助手回复，本任务不改页面。

## 实现要点

- 检查放在 `wire_tools` 之后、发起补全请求之前。超限不打开 generation span，不把原文写入 span。
- 日志只记 `trace_id`、`agent`、估算 token 和容量。
- `ctx.loop` 和业务直接调用的 `ctx.llm.chat` 走同一处。

## 验收标准

```bash
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/llm/test_context_budget.py -q
```

- [x] 估算超过容量时不请求薄网关，错误码为 `AGENT_CONTEXT_EXCEEDED`，消息为「上下文窗口过大」。
- [x] 未超过容量时请求仍发出。
- [x] `KEEL_CONTEXT_TOKEN_BUDGET=0` 时不检查。

## 明确不做

- 不裁剪旧工具输出，不做对话摘要。
- 不改会话历史的 6 条 / 400 字限制。
- 不改控制台页面。
