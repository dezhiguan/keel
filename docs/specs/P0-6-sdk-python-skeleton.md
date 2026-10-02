# P0-6 sdk-python 骨架

## 目标

`hello-agent` 能起来，`POST /v1/invoke` 返回合规 SSE，`/v1/health`、`/v1/manifest`、`/v1/feedback` 可用，`mount_to()` 能挂进一个现有的 FastAPI 应用而不影响原有路由。

## 依赖

- 前置任务：P0-1、P0-1a（契约冻结）、P0-2（pydantic 模型已生成）
- 依赖的契约文件：`invoke.openapi.yaml`、`sse-events.schema.json`、`manifest.schema.json`、`error-codes.yaml`
- 依赖的外部组件及其真实行为：这一期不接任何外部组件。LLM、知识库、审计都留桩，由 P0-7 / P0-8 填

## 改哪些文件

```
sdk-python/pyproject.toml
sdk-python/keel/__init__.py
sdk-python/keel/agent.py
sdk-python/keel/manifest.py
sdk-python/keel/asgi.py
sdk-python/keel/context.py
sdk-python/keel/protocol/{sse.py,events.py,errors.py}
sdk-python/tests/**
```

现状：`pyproject.toml`（包名 `keel-sdk`、入口 `keel = "keel.cli:main"`、`dependencies = []`）和 `keel/` 下全部模块的占位已存在。本任务在现有文件上补依赖和实现，不要重建目录结构。

## 接口契约

业务代码看到的形状（技术文档第 10 节）：

```python
from keel import Agent, Context
agent = Agent.from_manifest("agent.yaml")

@agent.entry
async def handle(req, ctx: Context):
    rules = await ctx.knowledge.search("dev-standards", req.input["text"], top_k=8)
    answer = await ctx.llm.chat(messages=[...])
    return ctx.final(answer, citations=rules.citations)

app = agent.asgi()
```

`Context` 这一期要有的成员：`step()`、`llm`、`knowledge`、`tools`、`delegate`、`gather`、`final`、`suspend`。除 `step`、`final`、`suspend` 外都可以是桩。

挂载两种用法都必须支持：

```python
app = agent.asgi()                 # 独立 ASGI 应用
keel.asgi.mount_to(existing_app)   # 挂进存量 FastAPI
```

## 实现要点

- **`mount_to` 不允许覆盖宿主应用的任何现有路由**。askdb 和 offshore-wind 都是存量 FastAPI 项目，迁移要求「原有接口先保留，前端逐步切到 `/v1/invoke`」。挂载前要检测路径冲突并直接报错，不要静默覆盖——静默覆盖会在迁移期把生产接口打掉。要有用例：宿主已有 `/v1/health` 时挂载失败并给出清晰错误。

- **SSE 出错发 `event: error`，不要断连**（铁律）。异常要在 SSE 生成器内部捕获并转成 error 事件，`code` 从 `error-codes.yaml` 读，不允许写字面量。生成器外层再兜一层，防止 error 事件本身构造失败时裸着断连。

- **客户端断开要能被感知并取消上游**。Starlette 里靠 `await request.is_disconnected()` 或 ASGI 的 `http.disconnect` 消息。断开后要停止继续产出、取消正在跑的协程。这条在 `.cursor/rules/testing.mdc` 里是必须有用例的场景，不是可选项。

- **`suspend` 发完要正常结束生成器，不是挂着等**（P0-1a）。实现成普通的「产出最后一个事件然后 return」，不要用任何形式的等待。这一期 `suspend` 只需要能发出合规事件、把 `run_id` 和 `resume_token` 填对；真正的挂起恢复编排在 P3-1。

- **`run_id` 由服务端生成**，`final`、`error`、`suspend` 三个事件都要带。`idempotency_key` 相同时返回原 `run_id` 的逻辑这一期**不做**（需要存储，在 P3-1），但字段要收下并留 `TODO` 写明。

- **manifest 校验用 `contracts/manifest.schema.json`，不要手写第二份**（`python-sdk.mdc`）。`manifest.py` 里的 pydantic 模型来自 P0-2 的生成产物，这里只做加载和校验，不重新定义字段。

- **业务代码里不允许出现任何上报调用**。`@agent.entry`、`ctx.*` 内部完成追踪、审计、护栏、配额。这一期虽然这些都是桩，但调用点的位置现在就要定对，后面 P0-7 / P0-8 只是把桩换成实现。桩的位置错了，后面两条任务都要返工。

- **配置只从环境变量读，不要写默认值兜底**（铁律）。`KEEL_LLM_BASE_URL` 之类缺失时直接启动失败并说清缺哪个，不要退到 localhost。

## 验收标准

```bash
cd sdk-python && pytest tests -q
```

- [ ] `hello-agent` 能起来，`curl` 打 `/v1/invoke` 收到 `step` → `token` → `final` 的合规 SSE
- [ ] 收到的事件逐条通过 `contracts/tests` 的 sse-events 校验
- [ ] 业务代码抛异常时收到 `event: error`，连接**没有**被直接断开，`code` 存在于 `error-codes.yaml`
- [ ] 客户端中途断开，服务端在 1 秒内停止产出（用例断言生成器被取消）
- [ ] `mount_to` 挂进一个已有 `/chat` 路由的 FastAPI，`/chat` 仍然可用
- [ ] 宿主已有 `/v1/health` 时 `mount_to` 报错，错误信息指出冲突路径
- [ ] 缺 `KEEL_*` 环境变量时启动失败并指出缺哪个
- [ ] `/v1/manifest` 返回的内容能通过 `manifest.schema.json`

## 明确不做

- 不接 LiteLLM、rag-forge、Langfuse、keel-audit，全部留桩（P0-7、P0-8）
- 不做换票、配额、护栏。`auth/`、`quota.py`、`guard/` 已按技术文档第 05 节放了只有 docstring 的占位，这一期**不实现、不删除**
- 不做 `ctx.delegate` 的真实实现，只留接口
- 不做 `idempotency_key` 的去重存储（P3-1）
- 不做 CLI（P0-10）
