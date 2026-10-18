# 技能版本、对比与升级

## 目标

工具与技能页能打开一个技能，查看版本历史并和上一版对比；编辑预填最新版并另存为下一版，旧版只读；使用方锁在旧版时可以升级并跑回归，也可以撤销授权。示例和用例可以有多条。

## 依赖

- 前置任务：R4-6。
- 依赖的契约文件：`contracts/console-api.openapi.yaml`。不新增错误码，不改 manifest 结构。
- 依赖的外部组件及其真实行为：回归沿用已有 `eval_run`。启动回归仍走 `EvalJobs.start`，没有就绪实例时返回 `GW_AGENT_OFFLINE`。不新增 Langfuse 接口。

## 改哪些文件

```
docs/specs/r1018/skill-versions.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/api/skills.ts
console/src/styles/console.css
console/src/views/tools/skillView.ts
console/src/views/tools/skillView.test.ts
console/src/views/tools/ToolRegistry.vue
keel-server/src/main/resources/db/migration/V27__skill_version_note.sql
keel-server/src/main/java/com/keel/server/skill/SkillController.java
keel-server/src/main/java/com/keel/server/skill/SkillService.java
keel-server/src/test/java/com/keel/server/skill/SkillFlowTest.java
```

## 接口契约

- `GET /skills/{name}`：版本列表（正文、示例、用例、变更说明、作者、时间）和使用方（锁定版本、`LATEST` / `BEHIND` / `RUNNING`）。
- `POST /skills`：同名追加下一版。`note` 在已有版本时必填。正文、示例、用例、依赖工具都没变则 400，不生成新版本。
- `DELETE /agents/{name}/skills/{skill}`：删除 `agent_skill_grant` 这一行，不写 `agent_tool_grant`。
- `POST /agents/{name}/skills/{skill}/upgrade`：把该智能体各环境最新一条 `agent_version.manifest_json` 里的 `spec.skills` 改到最新版，并启动回归。锁定版本仍是旧版，直到回归通过才改 `agent_skill_grant.version`。未通过则把 manifest 里的技能版本改回锁定版本。

V21、V22 已经进过库，不能改脚本。V27 去掉 `skill_version`、`agent_skill_grant`、`user_memory` 上的外键，并给版本补 `note`、`created_by`，给授权补回归状态。

## 界面对齐

- 原型位置：`docs/console/Keel-优化效果-20261018.html` 的「工具与技能」技能详情。版本列表在左，和上一版的行对比在右；使用方表格有升级并跑回归、撤销授权；编辑弹窗预填最新版正文。
- 对齐清单：无现成行。工具注册表和技能注册表保留，点进技能后用上面的详情布局。
- 页面数据来自 `GET /skills`、`GET /skills/{name}`、`POST /skills`、`DELETE /agents/{name}/skills/{skill}`、`POST /agents/{name}/skills/{skill}/upgrade`。标题旁保留原型的「提案」和「要做」，方便对照设计稿。

## 实现要点

- 编辑永远基于最新版另存。点旧版本只看对比，不能改那一版的正文。
- 升级先改库里的 manifest，再启动回归。运行中的 Pod 要重新部署才读到新版本；通过前不要部署，否则 manifest 版本和授权版本会短暂不一致。
- 回归进行中再次升级返回 400。已经是最新版没有升级按钮。
- 示例、用例在发布和另存时都是数组，空行丢掉。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=SkillServiceTest,SkillFlowTest,ArchitectureTest test
cd console && npm run gen:api && npx vitest related src/views/tools/skillView.ts src/views/tools/ToolRegistry.vue --run
```

- [ ] 另存新版本后旧版本仍可读取，内容相同不会新增版本。
- [ ] 撤销授权后 `agent_tool_grant` 行数不变。
- [ ] 回归通过后使用方锁定版本变为最新版；失败则 manifest 回到锁定版本。
- [ ] `skill_version`、`agent_skill_grant`、`user_memory` 没有外键。

## 明确不做

- 不改 V21、V22 已执行脚本。
- 不在回归通过时自动重新部署。
- 不改工具注册表的字段和抽屉。
- 不把原型里的演示数据和「提案」标签写进页面。
