# R1022 会话历史取消单条 400 字截断

## 目标

发给模型的 `session_messages` 不再按每条 400 字符截断，便于先观察上下文容量卡点；仍只取最近 6 条。同步去掉 SDK 对单条长度的拒绝，否则服务端放行后会被 SDK 打回。

## 依赖

- 前置任务：R1019 structured-session-history、R1022 context-token-budget。
- 依赖的契约文件：`contracts/invoke.openapi.yaml`（去掉会话历史单条 400 上限）。
- 依赖的外部组件及其真实行为：无。

## 改哪些文件

```
docs/specs/r1022/session-history-no-clip.md
docs/specs/r1019/structured-session-history.md
contracts/invoke.openapi.yaml
keel-server/src/main/java/com/keel/server/registry/service/DebugSessionService.java
sdk-python/keel/context.py
sdk-python/tests/loop/test_loop.py
```

## 接口契约

`context.session_messages` 仍最多 6 条，`role` 仅 `user`/`assistant`，`content` 为文本。`content` 的 OpenAPI `maxLength` 与本轮 `input.text` 对齐为 20000，不再是 400。超长内容是否调用模型由既有 `KEEL_CONTEXT_TOKEN_BUDGET`（默认 10000）卡点决定。

## 界面对齐

无。调试页仍展示完整 PG 消息；本任务只改下发给智能体的历史正文。

## 实现要点

- `DebugSessionService.history` 去掉 `substring(0, 400)`，保留 `LIMIT 6` 与 trim。
- SDK `Context` 构造去掉 `len(content) > 400` 校验；角色与条数校验不变。
- 原「401 字符拒绝」用例改为「长文本可接受」；系统/工具角色拒绝用例保留。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=AgentEndpointClientTest test
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/loop/test_loop.py -q
```

- [x] 服务端 history 返回超过 400 字的原文前缀不被截断。
- [x] SDK 接受超过 400 字的 session_messages；仍拒绝 system/tool 角色。
- [x] `invoke.openapi.yaml` 中 `session_messages.content.maxLength` 为 20000；助理镜像装入含该契约的 SDK 后，历史超 400 字不再因 schema 返回 `SERVER_INVALID_PARAM`。

## 明确不做

- 不取消最近 6 条上限。
- 不做摘要或工具输出裁剪。
- 不在本任务内完成 staging 部署；发布 SDK 与助理锁版本另走正式发布流程。

## R1023 后续调整

最近6条限制由 `p1/P1-context-window-recovery.md` 替代：最多128条带可选消息id的原文进入组装器，再按既有10000 token卡点选择完整轮次或摘要。原文存储不静默截断。
