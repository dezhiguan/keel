# R4-6 技能注册与授权

## 目标

控制台能发布技能、把某一版授给智能体。授权不会写入工具授权表。技能用例并进该智能体的评测数据集。SDK 只在 `ctx.skills.load` 时读取 manifest 锁定的版本。

## 依赖

- 前置任务：R1-3。
- 依赖的契约文件：`contracts/manifest.schema.json` 的可选 `spec.skills`；`contracts/error-codes.yaml` 的 `SKILL_NOT_GRANTED`；`contracts/console-api.openapi.yaml`。
- 依赖的外部组件及其真实行为：用例并入回归沿用已有的 `POST /api/public/datasets` 和 `POST /api/public/dataset-items`。不新增 Langfuse 接口。

## 改哪些文件

```
docs/specs/r1014/R4-6-skills.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/manifest.schema.json
contracts/error-codes.yaml
contracts/console-api.openapi.yaml
contracts/tests/fixtures/manifest/valid/agent.yaml
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
keel-common/src/main/java/com/keel/common/model/AgentManifest.java
sdk-python/keel/_generated/errors.py
sdk-python/keel/_generated/manifest.py
console/src/api/schema.d.ts
keel-server/src/main/resources/db/migration/V21__skill.sql
keel-server/src/main/java/com/keel/server/integration/langfuse/LangfuseClient.java
keel-server/src/main/java/com/keel/server/skill/SkillController.java
keel-server/src/main/java/com/keel/server/skill/SkillService.java
keel-server/src/test/java/com/keel/server/skill/SkillServiceTest.java
keel-server/src/test/java/com/keel/server/skill/SkillFlowTest.java
keel-server/src/test/java/com/keel/server/archunit/ArchitectureTest.java
sdk-python/keel/skills.py
sdk-python/keel/context.py
sdk-python/tests/skills/test_skills.py
console/src/api/skills.ts
console/src/views/tools/skillView.ts
console/src/views/tools/skillView.test.ts
console/src/views/tools/ToolRegistry.vue
console/src/views/agents/AgentDetail.vue
```

## 接口契约

三张表：`skill`、`skill_version`、`agent_skill_grant`。

`spec.skills` 可选，元素是 `{name, version}`。版本由 manifest 锁定。

- `GET /skills`、`POST /skills` 发布一版。风险取依赖工具里最高的一档。
- `GET /agents/{name}/skills` 列出已授权技能。
- `POST /agents/{name}/skills` body `{name, version}`。不写 `agent_tool_grant`。有用例时写入该智能体 manifest `spec.eval.dataset`。
- `GET /agents/{name}/skills/{skill}` 供 SDK 按需读取说明、示例和依赖工具名。未授权返回 `SKILL_NOT_GRANTED`。

## 界面对齐

- 原型位置：智能体详情 `#/agents` 技能 tab；工具与技能 `#/tools` 技能 tab。
- 对齐清单：技能 tab「已授权技能、版本、依赖工具、风险；添加技能」；工具与技能「工具 · 技能；技能注册表」。
- 页面数据来自 `GET /skills`、`GET /agents/{name}/skills`、`POST /agents/{name}/skills`、`POST /skills`。

## 实现要点

- 发布和授权都不调用工具授权。依赖工具要已经在工具表里，否则不能发布。
- 用例的数据集条目 id 由数据集名、技能名、版本和用例键算出。同一条再授一次不会插第二次。
- `ctx.skills.load` 先看 manifest。没声明，或读回的版本和锁定版本不一致，都是 `SKILL_NOT_GRANTED`。加载不会改 `ctx.tools`。

## 验收标准

```bash
python3 scripts/codegen.py && .venv/bin/python scripts/check_contracts.py
mvn -o -pl :keel-common -DskipTests install
mvn -o -pl :keel-server -Dtest=SkillServiceTest,SkillFlowTest,ArchitectureTest test
pytest sdk-python/tests/skills/test_skills.py
cd console && npx vitest related src/views/tools/skillView.ts src/views/tools/ToolRegistry.vue src/views/agents/AgentDetail.vue --run
```

- [x] 授权后 `agent_tool_grant` 没有新增行。
- [x] 技能用例出现在使用方的评测数据集，重复授权不重复插入。
- [x] 未在 manifest 声明的技能，`ctx.skills.load` 返回 `SKILL_NOT_GRANTED`，且不会因此获得工具调用权。

## 明确不做

- 不生成生产线改造任务，不自动走 H3。
- 不把用例放进运行时上下文。
- 不从经验记忆自动沉淀技能。经验来源只是注册时的一个标记。
