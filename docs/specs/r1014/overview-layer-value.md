# 总览分层价值

## 目标

运行总览「分层价值」按原型的三张卡展示业务、研发、元智能体指标。数得到的用 keel-server 已有账本计算，数不到的显示「未接入」。

## 依赖

- 前置任务：R4-3 已把总览改成 tab。本任务替换其中的占位数字。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `Overview` 增加可选 `layers`。
- 依赖的外部组件及其真实行为：不新增 Langfuse 或薄网关调用。点踩只读 `channel_feedback`。没有接地校验落库，无依据回答率保持未接入。

## 改哪些文件

```
docs/specs/r1014/overview-layer-value.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/overview/overviewFormat.ts
console/src/views/overview/overviewFormat.test.ts
console/src/views/overview/OverviewView.vue
keel-server/src/main/java/com/keel/server/insight/LayerValue.java
keel-server/src/main/java/com/keel/server/insight/LayerValueService.java
keel-server/src/main/java/com/keel/server/insight/OverviewService.java
keel-server/src/test/java/com/keel/server/insight/LayerValueTest.java
```

## 接口契约

`GET /insight/overview` 增加可选 `layers`：

- `biz`：`resolutionPct`、`handoffPct`、`downvotePct`、`ungroundedPct`
- `dev`：`firstPassPct`、`withinRoundsPct`、`avgDeliveryDays`、`takeoverPct`
- `meta`：`produced`、`upgraded`、`avgGateScore`、`humanPrPct`

百分比和天数是数字。分母为 0 时字段为空，页面显示「未接入」。

## 界面对齐

- 原型位置：`#/overview` 的「分层价值」tab，三张卡：业务智能体、研发智能体、元智能体。
- 对齐清单：运行总览「分层价值」。
- 页面数据来自 `GET /insight/overview` 的 `layers`。原型里的 82%、54%、7 / 1 只是布局，不写进页面。

## 实现要点

- 业务范围是当前环境里 `layer=BIZ`，或还没有 layer、分类为 biz 的智能体。
- 解决率 = 已结束运行里状态为 DONE 且没有转人工的占比。转人工率 = `suspend_reason=handoff` 的占比。点踩率 = `channel_feedback.value < 0` 的占比。
- 研发任务不含已取消。首次通过 = 进入过门禁或已结束、且修复轮次为 0 并通过的占比。3 轮内 = 已结束里 `DONE` 且 `fix_rounds < 3` 的占比。交付周期是 `DONE` 任务从创建到更新的平均天数。人工接管 = 有接管人或状态为 HUMAN 的占比。
- 元智能体：`layer=DEV` 且 `DONE` 的 CREATE / CHANGE 分别是生产和升级。门禁均分是这些研发员工最近一次 `eval_run.state=DONE` 的 `scoreTotal`。人工补写 PR 占比是这些任务里 `CODE` 产物 `origin=HUMAN` 的占比。没有 DEV 任务时生产和升级为空，不显示 0 / 0。
- 研发任务没有环境列，不按环境拆开。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=LayerValueTest test
cd console && npx vitest related src/views/overview/overviewFormat.ts src/views/overview/OverviewView.vue --run
```

- [ ] 10 次反馈里 1 次点踩，点踩率是 10。没有反馈时为空。
- [ ] 修复 0 轮并通过的任务计入首次通过；`DONE` 且修复轮次小于 3 计入 3 轮内。
- [ ] 没有 DEV 任务时，生产 / 升级显示未接入。
- [ ] 无依据回答率保持未接入。

## 明确不做

- 不调用 Langfuse Metrics API。
- 不把原型里的示例数字写进页面。
- 不估算没有落库的接地校验。
