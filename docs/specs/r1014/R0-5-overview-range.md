# R0-5 总览时间窗生效

## 目标

总览的 24 小时、7 天、30 天窗口分别返回对应的调用次数和已结算模型成本，控制台切换窗口后展示相同口径。

## 依赖

- 前置任务：R0-3。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `GET /insight/overview`，既有 `range=24h|7d|30d` 和 `Overview` 结构不变。
- 依赖的外部组件及其真实行为：Langfuse roots 按起止时间查询；本机 `SavedTraces.since` 按起点返回；薄网关 spend 明细由现有 `CostService.cost(env, range)` 过滤。行为均已由既有集成封装覆盖。

## 改哪些文件

```
docs/specs/r1014/R0-5-overview-range.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
keel-server/src/main/java/com/keel/server/insight/AgentUsageService.java
keel-server/src/main/java/com/keel/server/insight/OverviewService.java
keel-server/src/test/java/com/keel/server/insight/AgentUsageServiceTest.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
console/src/views/overview/OverviewView.vue
console/src/views/overview/overviewFormat.test.ts
console/src/views/overview/overviewFormat.ts
```

## 接口契约

`GET /api/v1/insight/overview?env={env}&range=24h|7d|30d`：`kpi.calls` 及 `kpi.modelCostCny` 使用选定窗口；`costByAgent` 与模型成本同窗。`agents[].calls24h`、`agents[].costCny` 和日预算告警仍是当日口径。未配置数据源继续返回 null。

## 界面对齐

- 原型位置：`#/overview` 顶部时间窗选择器。
- 清单对应行：`运行总览 #/overview / 时间窗`。R0-5 完成 24 小时、7 天、30 天；“今日 / 累计”由 R4-2 实现，本行分任务记录状态。
- 页面数据：`GET /api/v1/insight/overview?env&range`；切换时重载，同窗显示 KPI 与按智能体成本。

## 实现要点

- `AgentUsageService` 的缓存、并发加载按环境与时间窗分开；列表及其他调用者默认继续 24 小时。
- 当前窗口与前一等长窗口用于环比；累计调用仍由本机历史记录计算。
- 总览模型花费使用薄网关已结算 spend 明细，不能拿按上海日切的 Key `spentCny` 冒充 7 天或 30 天金额。日预算警告继续使用当日 Key 数字。
- 不把原有 `calls24h` 字段改成其他时间窗含义；控制台列表保持 24 小时标签。

## 验收标准

```bash
mvn -o -pl :keel-server test
cd console && npx vitest related src/views/overview/overviewFormat.ts src/views/overview/OverviewView.vue --run
```

- [x] 同一组不同日期的调用，在 24 小时、7 天、30 天返回各自正确的调用数；缓存窗口不串。
- [x] 三个窗口的已结算成本与 spend 明细求和一致，待计费金额不算作 0 元支出。
- [x] 控制台切换窗口后请求对应 `range` 并更新数字；清单记录 R4-2 尚未完成的两个窗口。

## 明确不做

- 不实现 R4-2 的“今日 / 累计”、token 与用户数汇总。
- 不改变智能体列表和预算告警的日口径。
