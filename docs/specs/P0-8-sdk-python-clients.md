# P0-8 sdk-python：llm / knowledge / audit reporter

## 目标

`ctx.llm` 调得通 LiteLLM、`ctx.knowledge` 调得通 rag-forge、审计能按风险分流写出去。全部用假服务做集成测试，不 mock client 类。

## 依赖

- 前置任务：P0-6（骨架，桩的位置已定）、P0-7（追踪，generation span 要在这里记录）
- 依赖的契约文件：`audit-event.schema.json`、`error-codes.yaml`
- 依赖的外部组件及其真实行为：LiteLLM、rag-forge、keel-audit。见 `.cursor/rules/external-apis.mdc`

## 改哪些文件

```
sdk-python/keel/llm/client.py
sdk-python/keel/knowledge.py
sdk-python/keel/audit/{reporter.py,masking.py}
sdk-python/keel/context.py        # 替换 P0-6 的桩
sdk-python/keel/asgi.py           # 创建 Context 时传入 manifest、工具注册表和客户端配置
sdk-python/keel/agent.py          # 启动时验证本任务所需的环境变量
sdk-python/keel/protocol/errors.py # 将客户端失败映射到已登记错误码
sdk-python/pyproject.toml         # openai SDK 运行时依赖
sdk-python/tests/integration/**   # 假 LiteLLM / rag-forge / keel-audit
```

## 接口契约

```python
await ctx.llm.chat(messages=[...], model=None)      # model 省略时用 manifest 的 models.default
await ctx.knowledge.search(kb, query, top_k=8)      # 返回带 citations
```

审计分流（铁律）：

| 风险 | 通道 | 失败时 |
|---|---|---|
| `risk: high` | 同步 HTTP `POST /api/v1/audit/events` | **拒绝执行业务** |
| 其余 | RocketMQ topic `keel-audit-events`，按 agent 顺序消息 | 转写本地文件后台重放 |

## 实现要点

- **厂商 SDK 只能用 openai 这一个，且必须指向 LiteLLM**。`llm/client.py` 用 openai SDK 但 `base_url` 指 LiteLLM、key 从 `KEEL_LLM_KEY` 读。禁止 import dashscope、anthropic 等（铁律）。加一条源码扫描测试钉死，和 P2-2 的静态扫描规则保持一致。

- **generation span 只由 SDK 记一次**。LiteLLM 自带的 Langfuse 回调必须是关闭的（P1-2 负责），否则一次调用会记两条。SDK 这边不需要做什么，但要在 `client.py` 顶部写一行注释说明这个前提，免得后来有人看到"怎么没接 Langfuse"又去开 LiteLLM 的回调。

- **队列满时丢追踪，绝不丢审计**（铁律）。审计 reporter 的有界队列满了之后，必须转写本地 jsonl 并后台重放，不允许丢弃、不允许阻塞业务。这和 P0-7 的追踪缓冲是**两套独立实现**，丢弃策略相反，不要复用。

- **同步审计写失败必须让业务失败**，不能 catch 了打个日志就过去。这是高风险动作的最后一道闸。要有用例：假 keel-audit 返回 500，`ctx.tools.call` 一个 high 风险工具时抛出 `AUDIT_WRITE_FAILED` 且工具**没有被执行**（断言假工具服务没收到请求）。

- **审计 payload 只允许白名单字段**（铁律）。`masking.py` 按 manifest 的 `audit.captureFields` 过滤，语义是「未列出的字段一律丢弃」，不是「列出的字段脱敏」。这两者写反了会把敏感字段写进审计库。用例要构造一个不在白名单里的敏感字段，断言它不出现在上报体里。

- **用假的 HTTP 服务做集成测试，不要 mock 掉整个 client 类**（`testing.mdc`）。mock client 类测不出协议问题——请求头、表单字段名、错误响应的形状，这些恰恰是最容易错的。假服务要能模拟超时、500、429 三种异常。

- **国内模型的成本坑**：百炼等模型如果 LiteLLM 的 `model_info` 里没配 `input_cost_per_token` / `output_cost_per_token`，成本会静默记 0，只打一行 WARNING。SDK 这边无法修复，但 `ctx.llm` 拿到的响应里如果 cost 为 0 且 token 数不为 0，应该打一条 WARNING 提示去检查 LiteLLM 配置。这能让问题在开发期就暴露，而不是等到月底对账。

- **rag-forge 调用要带 `caller_agent`**。P1-18 要按这个标签统计各调用方用量。从当前 agent 名取，不要让业务代码传。

## 验收标准

```bash
cd sdk-python && pytest tests/integration -q
```

- [ ] 假 LiteLLM 能收到合规的 OpenAI 兼容请求，`ctx.llm.chat` 返回结果且生成一条 `generation` span
- [ ] 假 rag-forge 收到的请求带 `caller_agent`，返回的 citations 能被 `ctx.final` 使用
- [ ] high 风险动作：假 keel-audit 返回 500 时抛 `AUDIT_WRITE_FAILED`，且假工具服务**没有**收到请求
- [ ] 审计队列填满后，事件转写到本地 jsonl；假服务恢复后被重放，一条不少
- [ ] 不在 `captureFields` 里的字段不出现在审计上报体里
- [ ] 源码扫描：没有 import dashscope / anthropic / 其他厂商 SDK
- [ ] 假 LiteLLM 返回 cost=0 但 token>0 时打出 WARNING
- [ ] 超时、500、429 三种异常都转成 `error-codes.yaml` 里登记的错误码，没有字面量

## 明确不做

- 不做工具调用的权限检查与审批挂起（`tools/` 在 P3-1；这一期 `ctx.tools.call` 只走审计和执行）
- 不做护栏（`guard/`）
- 不做换票（`auth/`）——这一期假设已经有可用的 token
- 不做 RocketMQ 的真实接入，异步通道这一期用假服务；真实接入在 P1-11 两边联调
