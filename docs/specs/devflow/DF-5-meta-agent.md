# DF-5 元智能体 meta-agent

## 目标

本次只做 **DF-5a（M1）**：在本地 `keel dev` 下，meta-agent 收到一份研发智能体的需求，产出需求单和按 `contracts/manifest.schema.json` 校验通过的 `agent.yaml` 草稿，然后以 `input_required` 挂起等人确认；人回复"确认"就结束并落盘产物，回复修改意见就按意见出下一版，最多改 3 轮。

M2～M4 只写边界，等前置到位再细化成可执行的 spec。

## 依赖

- 前置任务：无。M1 只用 SDK 已有能力：`@agent.entry`、`@agent.tool`、`ctx.llm`、`ctx.prompt`、`ctx.suspend`、`/v1/runs/{id}/resume`、keel-lite。
- 依赖的契约文件：`contracts/manifest.schema.json`、`contracts/invoke.openapi.yaml`（`input.text` 必填；resume 的 `input.text`）、`contracts/error-codes.yaml`。
- 依赖的外部组件及其真实行为：
  - 薄网关 `POST /v1/chat/completions`，模型 `qwen-plus`。meta-agent 最终需要编码模型，单价未核实，**M1 不用**。
  - **工具注册表现在读不到**：`GET /api/v1/tools` 只认控制台登录（`ConsoleSessionFilter`），智能体没有身份可用。M1 用可选的本地快照文件代替（见实现要点），服务身份读接口归 DF-2 / D0-4。
  - **挂起超过 10 分钟经 keel-server 恢复会被拒**（P3-1：`RUN_RESUME_DENIED`，委托授权未接）。所以 M1 的验收只在本地 `keel dev` / keel-lite 下进行。

## 改哪些文件

```
docs/specs/devflow/**
docs/README.md
docs/TASKS.md
agents/meta-agent/**
sdk-python/keel/asgi.py
sdk-python/tests/test_runtime.py
```

不改契约、不改 keel-server。需要的错误码全部用已登记的。

SDK 只修两处与契约不符的地方（开工时发现，M1 依赖它们）：

1. `reason=input_required` 的 resume 没有读请求体里的 `input.text`，智能体拿到的还是原始输入；契约要求此时 `input.text` 必填。修复后缺 `input.text` 返回 400 `SERVER_INVALID_PARAM`，且不会把运行标成已结束。keel-server 的 `AgentEndpointClient.resume` 本来就带 `input.text`，审批中心的"回复"也因此生效。
2. 恢复后智能体再次挂起时没有重新登记运行，第二次 resume 会因为 `resume_token` 对不上被拒。修复后与首次调用一样登记新的挂起记录。

## 接口契约

调用（契约要求 `input.text` 必填）：

```http
POST /v1/invoke
{"input": {"text": "<需求表单 JSON 或一段自然语言需求>"}}
```

需求表单 JSON（控制台新建向导"由智能体生产"提交的就是这个形状；字段都可选，缺的由模型补全并在需求单里标出"待确认"）：

```json
{
  "layer": "dev",
  "kind": "CREATE",
  "target_agent": "spec-agent",
  "title": "需求分析师",
  "goal": "把需求表单变成需求单和 agent.yaml 草稿",
  "users": "研发流水线内部调用",
  "io": "输入：需求表单 JSON；输出：需求单 + agent.yaml",
  "success": "manifest 一次通过 schema 校验率 ≥ 90%",
  "tools": ["devflow.catalog.search"],
  "knowledge": []
}
```

挂起（SDK 标准 suspend 事件）：`reason=input_required`，`ref` 为本次运行的 `run_id`，`prompt` 提示"回复「确认」结束，或直接写修改意见"。

恢复：

```http
POST /v1/runs/{run_id}/resume
{"resume_token": "<suspend 事件原样带回>", "input": {"text": "确认"}}
```

结束：`event: final`，`answer` 为 Markdown，包含需求单、`agent.yaml` 全文、产物落盘路径、M2 以后才做的事项。

## 实现要点

- **只做新建**：`kind` 为 `CHANGE` 时拒绝（`SERVER_INVALID_PARAM`）。改造要先读注册表里的现有 manifest，智能体现在没有身份可读，归 DF-5d。
- **只生产研发层**：表单里 `layer` 为 `biz` 时直接拒绝（`SERVER_INVALID_PARAM`，提示业务智能体由研发流水线生产）。`layer` 缺省按 `dev` 处理。
- **不能改自己**：需求里的 `target_agent` 等于 `meta-agent`，或 `layer` 不是研发层时，拒绝并返回 `DEVFLOW_LINEAGE_FORBIDDEN`。模型若把 `metadata.name` 写成 `meta-agent`，仍由 manifest 校验拒绝。名字不合规、改造尚未支持仍用 `SERVER_INVALID_PARAM`。
- **名字规则**：`metadata.name` 必须匹配 `^[a-z][a-z0-9-]{1,38}[a-z0-9]$`，这样拼上 `-{env}` 后正好满足薄网关别名规则。
- **校验走工具**：`manifest.validate`、`catalog.lookup` 两个本地工具都通过 `ctx.tools.call` 调用，链路上有 tool span，审计按白名单只记 `target_agent`、`version`。
- **manifest 校验**：用 SDK 同一份 `manifest.schema.json`（`jsonschema` Draft 2020-12），再加四条业务规则：
  1. `kind` 必须是 `Agent`；
  2. `metadata.name` 与需求一致；
  3. 每个 `risk: high` 的工具必须 `approval: required`；
  4. 有工具目录快照时，工具必须在目录里，且风险等级不得低于目录登记值。
- **工具目录快照**：环境变量 `META_TOOL_CATALOG` 指向一个 JSON 文件（`[{"name","risk","owner"}]`，从控制台工具页导出）。没设就不核对，需求单里给每个工具标"未核对注册表"。不设默认值。
- **模型输出**：要求模型只返回一个 JSON 对象 `{"spec": {...}, "manifest": {...}}`。解析失败或校验不过时，把错误原样回给模型重试 **1 次**；仍不过就以 `AGENT_MANIFEST_INVALID` 结束，消息里列出前 5 条错误。
- **现场**：按 `ctx.run_id` 存在 `.keel/meta-agent/{run_id}/state.json`（需求、当前版本号、需求单、manifest）。恢复时同一个 `run_id` 读回。`.keel/` 已在 `.gitignore` 里。K8s 下的持久化归 DF-5d（接 devflow 任务账本）。
- **确认判定**：回复去掉首尾空白后等于"确认"或 `confirm`（不区分大小写）才算确认；其余一律当修改意见。
- **修改上限**：第 3 轮修改后人仍不确认，以 final 结束并说明"已改 3 轮未确认，请人工编写或重新提交需求"，不再挂起。
- **产物落盘**：确认后写 `.keel/meta-agent/{run_id}/spec.json` 和 `agent.yaml`。M1 不生成项目骨架、不执行任何生成的代码。
- **提示词**：`draft`、`revise` 两个 text 提示词，正式版本在 Langfuse，`prompts/*.md` 是兜底副本。`ctx.llm.chat(..., prompt=p)` 让 generation 关联提示词版本。
- **配置**：只从环境变量读（`KEEL_LLM_BASE_URL`、`KEEL_LLM_KEY`、可选 `META_TOOL_CATALOG`），不写默认值兜底。
- **评测集**：`evals/seed.jsonl` 必须由人出题（例如用现有手写智能体的 manifest 反推需求）。本任务只放格式说明和 2 条示例，标 `TODO(人工)`，不让模型代写考题。

## 验收标准

```bash
cd agents/meta-agent && ../../.venv/bin/python -m pytest tests -q
cd sdk-python && ../.venv/bin/python -m pytest tests/test_runtime.py -q
```

- [ ] SDK：`input_required` 的 resume 把 `input.text` 交给智能体；缺 `input.text` 返回 400；恢复后再次挂起可以再恢复

- [ ] 合法需求：invoke 返回 `event: suspend`，`reason=input_required`；resume 回"确认"后返回 `event: final`，产物文件存在且 `agent.yaml` 能被 `keel.manifest.load_manifest` 加载
- [ ] 回复修改意见：再次挂起，`state.json` 里版本号 +1，模型收到上一版和意见
- [ ] 第 3 轮修改后仍不确认：final 结束，不再挂起
- [ ] 模型第一次给出非法 manifest、第二次合法：最终挂起成功，模型被调用 2 次
- [ ] 两次都非法：`event: error`，code 为 `AGENT_MANIFEST_INVALID`
- [ ] `layer=biz`、`target_agent=meta-agent`、名字不合规：`event: error`，code 为 `SERVER_INVALID_PARAM`，且不调用模型
- [ ] 配了工具目录快照、模型把 high 写成 low：校验不通过
- [ ] 源码里没有厂商 SDK 直连（`openai`、`dashscope`、`anthropic`）
- [ ] 测试用本地真实 HTTP 假薄网关，不 mock `LlmClient`

## 后续步骤（本次不做）

### M2 · DF-5b 生成骨架并在沙箱验证

- 前置：DF-3 沙箱；SDK 加 `keel new --from-spec spec.json`。
- 确认后用需求单生成项目骨架，填业务代码和单测，提交到沙箱跑 `pytest` 和协议自检；失败按报告修复，最多 3 轮。
- 生成的代码只在沙箱里执行，meta-agent 进程内不执行。

### M3 · DF-5c 仓库与人机协作

- 前置：DF-4 Git MCP（`git.repo.create`、`git.branch.push`、`git.pr.open`）和分支保护。
- 研发层任务强制人机协作：每个 PR 需要至少 1 个人工 approve；人写的提交作为基线，冲突时挂起问人。
- meta-agent 的仓库不在 `keel-agents/` 组织下，研发智能体都没有写权限。

### M4 · DF-5d 门禁、发布申请、任务账本

- 前置：DF-2（任务账本、谱系校验、服务身份）、D0-1（register / gate）、D0-3（委托授权）、DF-8（隐藏考题）。
- 注册 staging、跑门禁和隐藏考题；通过后调用 `git.pr.merge`（`risk: high`、`approval: required`）挂起等发布审批。
- 现场从本地文件改为 devflow 任务账本；每次状态变化写 `config.change` 审计（`kind=devflow.stage`）。

## 明确不做

- 不生产业务智能体（那是研发流水线 dev-lead 的事）。
- 不做改造任务（`kind=CHANGE`），归 DF-5d。
- SDK 的输入防注入护栏目前是空壳（`keel/guard/injection.py` 只有 TODO），manifest 里声明了 `inputInjection: enforce` 但暂不生效，本任务不补。
- M1 不生成代码、不执行代码、不建仓库、不调 keel-server。
- 不新增契约字段和错误码（归 DF-1）。
- 不让模型编写 meta-agent 自己的评测考题。
