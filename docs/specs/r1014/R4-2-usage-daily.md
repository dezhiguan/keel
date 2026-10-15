# R4-2 用量日账和总览累计

## 目标

总览可以选择今日、24 小时、7 天、30 天和累计。累计调用、token 和模型花费等于已封账各天之和加上今天的实时数，并与薄网关已结算账单一致。数不到的指标显示「未接入」。

## 依赖

- 前置任务：R4-1、R0-5。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的总览 `range` 增加 `today`、`all`，响应增加可选的累计行和指标口径。
- 依赖的外部组件及其真实行为：花费和 token 读现有薄网关账单，不新增外部接口。没有账单历史时不估算封账日前的数字。

## 改哪些文件

```
docs/specs/r1014/R4-2-usage-daily.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
keel-server/src/main/resources/db/migration/V20__usage_daily.sql
keel-server/src/main/java/com/keel/server/metering/**
keel-server/src/main/java/com/keel/server/insight/CostService.java
keel-server/src/main/java/com/keel/server/insight/OverviewService.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/test/java/com/keel/server/metering/UsageMathTest.java
keel-server/src/test/java/com/keel/server/archunit/ArchitectureTest.java
console/src/api/schema.d.ts
console/src/views/overview/overviewFormat.ts
console/src/views/overview/overviewFormat.test.ts
console/src/views/overview/OverviewView.vue
```

## 接口契约

- `GET /insight/overview?range=today|24h|7d|30d|all`。
- `cumulative`：`agents`、`calls`、`tokens`、`costCny`、`users`、`since`。数不到的数字为空。
- `metrics[]`：`key`、`definition`、`source`、`connected`。`connected` 为 false 时页面显示「未接入」。
- 表 `usage_daily(day, env, agent, calls, tokens, cost_cny, users, closed)`。

## 界面对齐

- 原型位置：`#/overview` 时间窗和累计一行。tab 留给 R4-3。
- 对齐清单：运行总览「时间窗」「累计一行」「指标口径」。
- 页面数据来自 `GET /insight/overview`。

## 实现要点

- 时区用上海。今天的行不封账。凌晨把昨天及更早的未封账行标成已封账。
- 累计调用、token、花费 = 已封账之和 + 今天实时。今天还没有数时，这三项为空，不把缺口当成 0。
- 累计用户是 `agent_run` 里去重的 `actor_user`，不把每天的去重人数相加。
- 在册智能体含已下线、不含已删除。
- 花费只加已结算账单。同一环境的今日花费等于这些账单的 `costCny` 之和。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=UsageMathTest,CostServiceTest,ArchitectureTest test
cd console && npx vitest related src/views/overview/overviewFormat.ts src/views/overview/OverviewView.vue --run
```

- [x] 已封账 10 次调用、100 token、1.50 元，今天 3 次、30 token、0.40 元，累计是 13、130、1.90。
- [x] 今天没有实时数时，累计调用为空。
- [x] 总览时间窗包含今日和累计，指标能显示口径；未接入显示「未接入」。

## 明确不做

- 不把总览改成四个 tab（R4-3）。
- 不估算日账上线前的历史。
- 不把各天的去重用户相加当作累计用户。
