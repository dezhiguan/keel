# R1-8 智能体要素检查

## 目标

`spec.kind: agent` 登记前检查五项核心声明，缺项返回具体名称；详情页显示 12 项要素及智能体或工作流型标识。

## 依赖

- 前置任务：R1-1 `spec.kind`、R1-3 `ctx.loop`。
- 依赖的契约文件：`contracts/manifest.schema.json`、`contracts/console-api.openapi.yaml`。新增字段均可选，保持 `keel/v1`。
- 依赖的外部组件及其真实行为：本任务只检查 manifest 声明及现有本地自检结果，不新增 Langfuse、薄网关或 auth-gateway API 假设。

## 改哪些文件

```
docs/specs/r1014/R1-8-agent-elements-check.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/manifest.schema.json
contracts/console-api.openapi.yaml
keel-common/src/main/java/com/keel/common/model/AgentManifest.java
sdk-python/keel/_generated/manifest.py
keel-server/src/main/java/com/keel/server/registry/service/ManifestValidator.java
keel-server/src/main/java/com/keel/server/registry/model/dto/AgentElements.java
keel-server/src/main/java/com/keel/server/registry/model/dto/AgentDetail.java
keel-server/src/test/java/com/keel/server/registry/ManifestCheckTest.java
keel-server/src/test/java/com/keel/server/registry/model/AgentDetailTest.java
console/src/api/schema.d.ts
console/src/views/agents/AgentDetail.vue
```

## 接口契约

- `spec.capabilities?` 含可选 `toolCalling`、`loop`、`memory` 布尔值。它们是能力声明，不单独证明运行时行为。
- 显式 `spec.kind: agent` 要求：`tools` 非空且 `capabilities.toolCalling: true`、`capabilities.loop: true`、`capabilities.memory: true`、`prompts.source: langfuse` 且 `items` 非空、`eval.dataset` 非空。缺项返回 `AGENT_MANIFEST_INVALID` 和缺项名称；`workflow` 与未声明类型按现有兼容规则登记。
- `AgentDetail.elements?` 为 `{kind, items, missing}`；`kind` 是 `agent` 或 `workflow`，`items` 固定 12 项，每项有 `name`、`passed`（true/false/null）、`detail`。`missing` 只列五项核心缺项。运行时证据缺失用 `null`，不能冒充已验证。

## 界面对齐

- 原型位置：`#/agents` 详情抽屉 → 概览 → “要素检查”，与“运行方式”相邻。
- 清单对应行：智能体目录的“概览 · 要素检查”R1-8 行。
- 页面数据：`GET /api/v1/agents/{name}` 的 `elements`，不在前端按名称推断能力。

## 实现要点

- 契约先改并运行模型生成，再改 Java 校验和控制台。登记前校验，避免缺项时已发放资源或写入版本。
- 12 项区分声明和真实证据：没有运行时证据的项目显示“待验证”；工作流型不受五项核心门禁约束，但仍显示各项。
- 本任务不静态扫描 Python/Java 源码，也不以 manifest 自声明冒充实际工具调用、loop 或记忆运行证据；后续门禁仍需行为测试。

## 验收标准

```bash
python3 scripts/codegen.py
python3 scripts/check_contracts.py
mvn -o -pl :keel-server test
cd console && npm run gen:api && npm run typecheck && npx vitest related src/views/agents/AgentDetail.vue --run --passWithNoTests
```

- [ ] agent 缺提示词或任一核心声明时登记前拒绝，并返回缺项。
- [ ] workflow 可以不声明五项核心能力而登记。
- [ ] 详情页显示 12 项、类型与缺项，未验证的运行证据不标为通过。

## 明确不做

- 不用假探针结果证明真实工具调用、loop 或长期记忆行为；不改生产发布门禁。
