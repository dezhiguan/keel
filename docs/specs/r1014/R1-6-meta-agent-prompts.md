# R1-6 元智能体提示词交付

## 目标

元智能体生成的岗位项目声明提示词、知识与护栏，交付 `prompts/` 文件，运行代码从 `ctx.prompt()` 取提示词；注册时把首次提示词同步到 Langfuse，代码审查必须检查硬编码提示词。

## 依赖

- 前置任务：阶段 1 已完成 R1-1 至 R1-5。
- 依赖的契约文件：`contracts/manifest.schema.json` 的 `prompts.items`、`knowledge`、`guardrails`；`contracts/console-api.openapi.yaml` 的 `POST /agents/{name}/prompts/sync`。
- 依赖的外部组件及其真实行为：Langfuse Cloud v4 提示词 `POST /api/public/v2/prompts` 由 keel-server 既有 `PromptService.sync` 封装；本任务只调用已有 keel-server 同步接口，不新增外部接口假设。Git 提交号由交付仓库或 CI 提供。当前研发创建入口尚未开通知识库授权，因此 `knowledge: []`。

## 改哪些文件

```
docs/specs/r1014/R1-6-meta-agent-prompts.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
agents/meta-agent/candidate.py
agents/meta-agent/app.py
agents/meta-agent/workflow.py
agents/meta-agent/tests/test_meta_agent.py
agents/meta-agent/tests/test_candidate.py
agents/meta-agent/tests/test_workflow.py
agents/meta-agent/tests/test_prompt_delivery.py
sdk-python/keel/cli/register.py
sdk-python/tests/cli/test_prompt_register.py
keel-server/src/main/java/com/keel/server/devflow/MetaAgentExecution.java
keel-server/src/test/java/com/keel/server/devflow/MetaAgentRulesTest.java
```

## 接口契约

- 生成 manifest 的 `spec.prompts = {source: langfuse, items: [{name: role, type: text}]}`；`spec.knowledge = []`；`spec.guardrails = {inputInjection: enforce, grounding: off, pii: mask}`。
- 岗位候选文件为 `role.py`、`prompts/role.md`、`tests/test_role.py`。`role.py` 必须调用 `ctx.prompt("role")`，不接受大段内联提示词。
- `keel register` 在注册成功后按已声明的本地提示词调用 `POST /api/v1/agents/{name}/prompts/sync`，body 为 `gitSha` 和含 `name/type/prompt/sha256` 的 `files`；无有效 Git 提交时在注册前报错。
- 代码评审 `checks` 增加 `hardcodedPrompt`，高或中风险发现、任一维度失败均不能通过。

## 界面对齐

- 原型位置：`#/prompts/{智能体}/{名称}` 的版本与内容 tab，以及 `#/jobs` 候选交付后的审查结果。
- 清单对应行：提示词工程页面的“元智能体交付数据接入”行；R1-6 接通后端数据来源，R4-5 的页面改造仍未开始。
- 页面数据：`GET /api/v1/agents/{name}/prompts/{prompt}` 和已有研发任务详情接口。

## 实现要点

- 只从确认需求和已授权目录生成 manifest；模型回复不能自行扩展知识库或降低护栏。
- 交付重试应保留 `prompts/role.md`，候选校验必须拒绝缺文件、空文件与硬编码长提示词。
- CLI 同步复用 keel-server 的现有接口与哈希规则；不可直接从 SDK 调 Langfuse，也不可在日志打印提示词原文。

## 验收标准

```bash
cd sdk-python && PYTHONPATH=.:../agents/meta-agent ../.venv/bin/python -m pytest tests/cli/test_prompt_register.py -q
cd keel-server/.. && mvn -o -pl :keel-server test
cd agents/meta-agent && ../../.venv/bin/python -m pytest tests/test_prompt_delivery.py -q --noconftest
```

- [x] 生成 manifest 含 `prompts.items`、空的授权知识列表和护栏；交付目录与 Git 文件列表保留 `prompts/role.md`。
- [x] 候选代码只能通过 `ctx.prompt("role")` 取提示词；代码审查含 `hardcodedPrompt`。
- [x] 使用假的 keel-server HTTP 响应验证注册后同步请求；keel-server 现有 PromptFlowTest 覆盖向假 Langfuse 创建提示词（本机无 Docker，相关测试跳过）。真实云端验收需有对应账号和环境。

### 云端验收（由有账号的操作者执行）

1. 在新交付项目的 Git 提交上运行 `keel register --env staging`，确认注册和 `/prompts/sync` 均成功；不要在未提交的工作区使用临时 SHA。
2. 在 Langfuse Cloud 日本节点查看 `{agent}/role` 的第一版及 `dev`、`test`、`staging` 标签；核对内容与 `prompts/role.md` 一致。
3. 打开控制台 `#/prompts/{agent}/role`，核对版本、内容与标签，并确认源码中没有大段岗位提示词字符串。
4. 若任一步失败，记录 HTTP 状态、错误码和 traceId，不记录提示词原文或凭证。

## 明确不做

- 不开通知识库权限、不修改 Langfuse 既有接口规则、不执行线上注册或写操作。
