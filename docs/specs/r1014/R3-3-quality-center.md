# R3-3 质量中心

已撤回。第 3 版控制台原型没有这项，侧栏、页面和 `/quality`、`/quality/cases` 已删除。总览用的质量分数聚合不在这次范围内。

## 目标

质量中心不再是占位页。它显示用户反馈数和点踩率，点踩的调用可以加入该智能体的评测集，下一次读取这个数据集的回归会包含该用例。

## 依赖

- 前置任务：无。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 新增 `GET /quality` 和 `POST /quality/cases`。
- 依赖的外部组件及其真实行为：只读 `GET /api/public/v3/scores` 和 `GET /api/public/v2/observations`，写入沿用已有的 `POST /api/public/datasets` 与 `POST /api/public/dataset-items`。反馈分数的名字是 `user-feedback`，小于 0 视为点踩。

## 改哪些文件

```
docs/specs/r1014/R3-3-quality-center.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/api/quality.ts
console/src/views/quality/QualityView.vue
console/src/router/nav.ts
console/src/router/nav.test.ts
keel-server/src/main/java/com/keel/server/integration/langfuse/LangfuseClient.java
keel-server/src/main/java/com/keel/server/insight/QualityCenterService.java
keel-server/src/main/java/com/keel/server/insight/QualityController.java
keel-server/src/test/java/com/keel/server/insight/QualityCenterServiceTest.java
```

## 接口契约

- `GET /api/v1/quality?agent=` 返回 `connected`、`feedbackCount`、`downCount`、`downRate`、`cases[]`。Langfuse 未配置时 `connected` 为 false，点踩率为空，不把未接入显示成 0。
- `POST /api/v1/quality/cases` body `{agent, traceId}`。只接受该智能体的点踩。写入 manifest `spec.eval.dataset`。同一 trace 再次加入不新增第二条。
- 数据集条目带 `metadata.traceId` 和 `metadata.source=user-feedback`，`input` 是调用输入，`expectedOutput` 是当时的回复。

## 界面对齐

- 原型位置：历史原型有质量中心；第 3 版左侧菜单未单列。清单单独有「质量中心」一行，按该行做，不改 R4-3 的菜单重命名。
- 对齐清单：质量中心 R3-3。
- 页面数据：`GET /api/v1/quality` 和 `POST /api/v1/quality/cases`。

## 实现要点

- 正确性等其他分数不计入反馈和点踩率。
- 没有评测集时拒绝加入，不新建一个临时数据集名。
- 侧栏增加「质量中心」入口，否则占位页替换后仍然进不去。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=QualityCenterServiceTest test
cd console && npm run gen:api && npm run typecheck && npx vitest related src/views/quality/QualityView.vue src/router/nav.test.ts --run
```

- [x] 一条点踩、一条点赞时点踩率是 0.5，点踩行带上观测到的输入和回复。
- [x] 加入评测集的请求写到该智能体的 dataset，再次加入不换 itemId；点赞不能加入。
- [x] Langfuse 未配置时页面显示未接入，而不是 0% 点踩率。

## 明确不做

- 不在 SDK 里补 `POST /v1/feedback` 写分数；页面只读已经记在 Langfuse 的 `user-feedback`。
- 不改总览里已有的质量平均分计算。
- 不在线上写入 Langfuse。
