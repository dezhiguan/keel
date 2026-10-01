# P0-10 CLI：new / dev

## 目标

`keel new hello-agent` 生成的项目直接 `keel dev` 能起来，改代码热重载，终端里能看到追踪和审计，审批能在本地批。

## 依赖

- 前置任务：P0-6（SDK 骨架）、P0-9（keel-lite）
- 依赖的契约文件：`manifest.schema.json`
- 依赖的外部组件及其真实行为：Langfuse dev 项目（`keel dev` 的追踪往那边发）。本地没有 Langfuse 时要能降级，见实现要点

## 改哪些文件

```
sdk-python/keel/cli/{__init__.py,main.py,new.py,dev.py}
sdk-python/pyproject.toml          # console_scripts 入口 keel
sdk-python/tests/cli/**
```

模板文件在 `templates/`，由 P0-13 提供。本任务只做 `new` 的渲染逻辑，内置一个最小的 `hello-agent` 模板自测用。

## 接口契约

```
keel new <name> [--template chat-rag|tool-agent|graph-agent|supervisor|java-spring]
keel dev [--port 8000] [--no-trace]
```

`keel new` 生成技术文档第 05 节的智能体项目结构：

```
{agent}/
├── agent.yaml  app.py  tools/  prompts/
├── evals/{seed.jsonl,scorers.py}
├── tests/  Dockerfile  .github/workflows/keel.yml
└── .gitignore           # 必须包含 .keel/
```

`keel dev` 做四件事：起 keel-lite、起智能体 ASGI 应用、开热重载、把追踪指向 Langfuse dev 项目。

## 实现要点

- **CLI 和运行时必须同一个包、同一个版本号**（`python-sdk.mdc`）。不要拆成 `keel-cli` 和 `keel-sdk` 两个包——版本错配时报出来的错会非常难懂。`pyproject.toml` 里一个 `console_scripts` 入口，就这样。

- **`keel dev` 的验收标准是 30 分钟**。P0 整期的验收是「`keel new hello-agent` 后 30 分钟内在本地看到追踪和审计」。这意味着 `keel dev` 不能要求用户先配一堆环境变量。但铁律又说「配置只从环境变量读，不要写默认值兜底」——这两条的调和方式是：**`keel dev` 自己往环境里注入 dev 专用的值并在启动时逐条打印出来**，而不是在 SDK 里写默认值。SDK 侧的行为不变（缺了就失败），是 CLI 负责在 dev 环境下把它们填上。这个区分要写进代码注释，不然后面有人会把默认值挪进 SDK。

- **没有 Langfuse 时要能跑**。不是每个人第一天就有 dev 项目。`--no-trace` 显式关闭，以及连不上时自动降级成「span 只落本地 jsonl + 终端打印树状结构」，并打一条醒目提示告诉用户怎么配。**不要因为追踪连不上就起不来**——那会直接毁掉 30 分钟验收。

- **热重载不能把 keel-lite 的 SQLite 连接重置掉**。reload 时子进程重启，如果 keel-lite 跟着重启，正在挂起的审批单看起来就"消失"了（实际还在文件里，只是列表没刷新）。keel-lite 要么跑在父进程、要么每次重连同一个文件。要有用例：挂起审批 → 改一行业务代码触发 reload → 审批单仍在。

- **`keel new` 生成的 `.gitignore` 必须包含 `.keel/`**。本地审计库里有用户原文（白名单字段），误提交等于泄漏。这条容易漏，列进验收。

- **生成的 `agent.yaml` 必须能直接通过 schema 校验**。不要生成带 `TODO` 占位符而无法校验的文件——用户第一次 `keel dev` 就报错，体验很差。`metadata.name` 从命令行参数填，`runtime.endpoint` 填本地地址，其余用默认值。

- **`keel new` 不要联网**。模板随包发布，离线可用。

- 这一期只做 `new` 和 `dev` 两个子命令。`eval` / `register` / `release` / `retire` / `gate` 的入口可以先注册但执行时提示"尚未实现"，不要留空指针。

## 验收标准

```bash
cd sdk-python && pytest tests/cli -q
# 端到端手测
keel new hello-agent && cd hello-agent && keel dev
```

- [ ] `keel new hello-agent` 生成的项目结构完整，`agent.yaml` 通过 `manifest.schema.json`
- [ ] 生成的 `.gitignore` 包含 `.keel/`
- [ ] `keel dev` 起得来，`curl /v1/invoke` 收到合规 SSE
- [ ] 改 `app.py` 一行，热重载生效，不用手动重启
- [ ] 挂起一个审批后触发热重载，审批单仍在且可批准
- [ ] 配好 Langfuse dev 项目时，调用一次在 Langfuse 里能看到节点树
- [ ] **没有** Langfuse 配置时 `keel dev` 仍能起来，span 落本地并在终端打印树，有醒目提示
- [ ] `keel dev` 启动时逐条打印它注入了哪些 dev 环境变量
- [ ] 从零开始（空目录）到看到追踪和审计，全程 30 分钟内

## 明确不做

- 不做 `eval` / `register` / `release` / `retire` / `gate` 子命令（分别在 P2-4、P1-4、P2-11、P2-10、P2-1）
- 不做五个正式模板（P0-13），本任务只内置一个最小 hello-agent
- 不做 Java 项目的 `keel new`（`java-spring` 模板在 P0-13）
- 不做模板的远程下载和版本管理
