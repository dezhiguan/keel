# 研发任务门禁列表

## 目标

评测与门禁页的「研发任务门禁（隐藏考题）」tab 列出已到门禁或已完成的研发任务，以及有评测分的人工编写智能体。分数来自任务状态和隐藏考题聚合结果，不展示考题正文。

## 依赖

- 前置任务：DF-8 隐藏考题结果表；R4-3 已有该 tab 的占位。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 新增 `GET /eval/gates`。字段全部可选。
- 依赖的外部组件及其真实行为：人工编写智能体的可见分沿用现有 Langfuse / 评测执行器最新分。查不到时该行不编造。

## 改哪些文件

```
docs/specs/r1014/eval-gates.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/api/eval.ts
console/src/views/eval/EvalView.vue
keel-server/src/main/java/com/keel/server/devflow/GateBoard.java
keel-server/src/main/java/com/keel/server/insight/EvalQueryService.java
keel-server/src/main/java/com/keel/server/insight/controller/EvalController.java
keel-server/src/test/java/com/keel/server/devflow/GateBoardTest.java
```

## 接口契约

`GET /api/v1/eval/gates?env=`

`data.rows[]`：`agent`、`jobId`、`visible`、`holdout`、`passed`。`jobId` 为空表示人工编写。`visible` / `holdout` 为空表示没有记到分数。`passed` 只在有可见分时给出，可见分四舍五入到两位后 ≥ 0.80 为通过。响应不含考题正文、`visibleByTag` 或种子用例。

## 界面对齐

- 原型位置：`docs/console/Keel-控制台前端-20261014.html` 的 `#/eval`，tab「研发任务门禁（隐藏考题）」。表列：智能体、来源、可见用例、隐藏考题（聚合）、分差、结论。
- 对齐清单：`docs/specs/r1014/ui-checklist.md` 评测与门禁的「研发任务门禁」行。
- 页面数据来自 `GET /api/v1/eval/gates`。任务来源链到 `/jobs/{jobId}`。没有隐藏考题的人工编写智能体，隐藏考题和分差显示「—」。任务已到门禁但没有分数时显示「未接入」。

## 实现要点

- 只收录阶段为 `GATE`、`H4`、`RELEASE`、`WATCH`，或状态为 `DONE` 的任务。
- 有 `prodGate` 时用它，否则用 `gate`。状态里没有隐藏分时，用 `devflow_holdout_result.score`。
- 分差沿用现有 `holdoutGap`：隐藏分减可见分，绝对值超过 0.10 标红。
- 去掉该 tab 的「占位」。没有行时显示空状态，不回退成示例数字。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=GateBoardTest test
cd console && npx vitest related src/views/eval/evalCopy.test.ts --run
```

- [ ] 到门禁的任务带可见分、隐藏分和通过与否；没有分数的列为空
- [ ] 人工编写且有评测分的智能体隐藏考题为空
- [ ] 响应里没有考题正文

## 明确不做

- 不在控制台返回隐藏考题正文
- 不改 `keel gate` 的计分门槛
- 不把未到门禁的研发任务放进这张表
