# R1-2 模型工具调用结果

## 目标

Python SDK 的 `ctx.llm.chat` 接受可选 `tools`，调用薄网关后返回模型文本和结构化 tool_calls，同时不改变未传工具的调用方。

## 依赖

- 前置任务：R1-1。
- 依赖的契约文件：无跨语言新增结构；模型响应按既有 OpenAI 兼容 `chat.completion` 形状。
- 依赖的外部组件及其真实行为：`.cursor/rules/external-apis.mdc` 规定薄网关 `POST /v1/chat/completions`；仓库 `Gateway.complete` 对请求 JSON 仅替换 `model` 后转发，并原样返回成功响应。用本地假 HTTP 响应验证 `tools` 和 `tool_calls` 形状。

## 改哪些文件

```
docs/specs/r1014/R1-2-llm-tool-calls.md
docs/specs/r1014/README.md
sdk-python/keel/llm/client.py
sdk-python/tests/integration/test_clients.py
```

## 接口契约

```python
async def chat(messages: list[dict], model: str | None = None, prompt=None,
               tools: list[dict] | None = None) -> str | ChatResult: ...

@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]

@dataclass(frozen=True)
class ChatResult:
    content: str
    tool_calls: tuple[ToolCall, ...]
```

未传 `tools` 时返回原有 `str`；传入 `tools`（包括空列表）时返回 `ChatResult`。发送给薄网关的工具声明采用 OpenAI 兼容 `tools` 数组；工具调用参数从 JSON 字符串解析为对象。

## 界面对齐

无。`ui-checklist.md` 无 R1-2 行；此任务只提供后续 R1-3 的 SDK 基础能力。

## 实现要点

- 继续经现有虚拟 Key 访问薄网关；不直连厂商。
- generation span 保留现有 token 与内容记录；无工具时返回值和错误行为保持不变。
- 工具参数不是 JSON 对象时明确失败，不把无效参数交给工具执行。

## 验收标准

```bash
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/integration/test_clients.py -q
```

- [x] 现有不传工具的调用仍返回字符串。
- [x] 假薄网关收到 `tools`，SDK 返回内容、调用 ID、工具名和解析后的参数对象。
- [x] 无工具调用时返回空 `tool_calls`，格式错误的参数不能执行。

## 明确不做

- 不实现工具执行循环和审批恢复，归 R1-3。
- 不改变 Java starter 的空占位 `LlmClient`；Java 运行时能力在后续 starter 任务中独立实现。
