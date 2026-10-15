# R4-5 结构化提示词

## 目标

提示词详情能看分段、变量 schema、输出契约，并能试编译。结构放在 Langfuse config 的 `keel` 里，正文仍是编译后的 text / chat。删掉一条约束时指出它对应的评测维度。

## 依赖

- 前置任务：无。`PROMPT_VARIABLE_MISSING` 已在契约里。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的提示词详情、差异和试编译。
- 依赖的外部组件及其真实行为：2026-10-15 从跳板机 `8.163.63.222` 实测 `https://jp.cloud.langfuse.com`。Server 3 能解析该域名，TCP 超时；项目 Key 在 Secret `keel-system/keel-server`。探针提示词 `keel-probe/r4-5-config-20261015`。
  - `POST /api/public/v2/prompts` 返回 201。body 字段是 `name`、`type`、`prompt`、`config`、`commitMessage`。
  - `config` 为对象。`temperature`、`max_tokens`、`top_p` 与嵌套对象 `keel` 原样保存。
  - `GET /api/public/v2/prompts/{name}?version=1` 返回 200。`config` 与 `commitMessage` 和写入一致。`commitMessage` 与 `config` 平级。
  - 未传 labels 时响应 `labels` 含 `latest`。GET 比 POST 多 `resolutionGraph`。

## 改哪些文件

```
docs/specs/r1014/R4-5-structured-prompts.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
.cursor/rules/external-apis.mdc
docs/architecture/Keel-技术文档.html
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
keel-server/src/main/java/com/keel/server/prompt/PromptTexts.java
keel-server/src/main/java/com/keel/server/prompt/PromptService.java
keel-server/src/main/java/com/keel/server/prompt/PromptController.java
keel-server/src/test/java/com/keel/server/prompt/PromptTextsTest.java
keel-server/src/test/java/com/keel/server/prompt/PromptFlowTest.java
sdk-python/keel/prompts.py
sdk-python/tests/prompts/test_prompts.py
sdk-python/tests/integration/test_clients.py
console/src/api/prompts.ts
console/src/views/prompts/promptView.ts
console/src/views/prompts/promptView.test.ts
console/src/views/prompts/PromptDetail.vue
```

## 接口契约

`config.keel`：

```json
{
  "sections": [{ "kind": "constraint", "id": "c-1", "text": "不要编造", "dimension": "拒答 / 无法确认" }],
  "variables": [{ "name": "question", "type": "string", "source": "用户输入", "required": true, "limit": "2000 字", "pii": true }],
  "outputContract": { "type": "object" },
  "samples": [{ "name": "样例", "variables": { "question": "你好" } }]
}
```

分段 `kind` 只认 `role`、`task`、`constraint`、`tool`、`output`、`example`。约束必须有 `dimension`。

- 保存新版本仍是 `POST /agents/{name}/prompts/{prompt}/versions`。`fromSections: true` 时用分段编译正文。响应增加 `removedConstraints`。
- `GET .../versions/{version}` 增加 `structure` 和 `checks`。
- `GET .../diff` 增加 `removedConstraints`。
- `POST .../trial`，body `{version, variables}`，返回编译结果。缺变量返回 `PROMPT_VARIABLE_MISSING`。

## 界面对齐

- 原型位置：`docs/console/Keel-控制台前端-20261014.html` 提示词详情 `#/prompts/{智能体}/{名称}`，tab 为版本与内容、结构化、变量与输出契约、试运行。
- 对齐清单：提示词工程的 tab 行，以及结构化「分段显示；删除约束时提示对应评测维度」。
- 页面数据来自 `GET /agents/{name}/prompts/{prompt}/versions/{version}`、`GET .../diff`、`POST .../versions`、`POST .../trial`。

## 实现要点

- 模型参数仍只有 `temperature`、`max_tokens`、`top_p`。`config.model` 拒绝。`keel` 只存结构。
- 正文编辑保存时把已有 `keel` 原样带上，避免把分段冲掉。
- 对比上一版时，约束正文消失即视为删除，带上该约束的 `dimension`。
- SDK 的 `prompt.config` 不含 `keel`。`prompt.structure` 单独存放。`ctx.llm.chat` 只转发那三项模型参数。
- 试运行只做变量替换。不调用薄网关，不写账单。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=PromptTextsTest,PromptFlowTest test
pytest sdk-python/tests/prompts/test_prompts.py sdk-python/tests/integration/test_clients.py
cd console && npx vitest related src/views/prompts/promptView.ts src/views/prompts/PromptDetail.vue --run
```

- [x] 删掉带维度的约束后，响应和页面都写出该评测维度。
- [x] 模板变量或 schema 里的必填变量缺失时返回 `PROMPT_VARIABLE_MISSING`。
- [x] SDK 读到 `keel` 后，`prompt.config` 和模型请求里都没有这段。

## 明确不做

- 不从 manifest 自动生成工具分段。
- 试运行不调用模型，不展示回答、token 或费用。
- 不估算上下文窗口占用。
- 不改提示词标签规则，也不删除这次实测留下的探针提示词。
