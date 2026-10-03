# P1-14 insight：总览、成本、质量与共享服务

## 目标

总览接口的调用量、成本和评分来自 Langfuse 与 LiteLLM，不再是 null。模型网关接口按智能体、按模型给出与 LiteLLM 对得上的人民币花费。质量和共享服务的数据能被后续页面直接用。

## 依赖

- 前置任务：P1-13、P1-2。告警列表依赖 P1-9 的 `reconcile_finding`，没有 finding 时 `alerts` 为空数组。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `Overview`、`ModelGateway`、`SharedServices`
- 依赖的外部组件：
  - Langfuse：`GET /api/public/v2/metrics`、`GET /api/public/v3/scores`。不用 v3 的 `/api/public/metrics`、`/scores`
  - LiteLLM：`GET /spend/logs/v2`（分页拉完）、`GET /global/spend/report`、`GET /key/info`。不用 `/spend/logs`
  - Prometheus：`GET /api/v1/query`，只供共享服务指标

## 改哪些文件

```
keel-server/src/main/java/com/keel/server/insight/OverviewService.java
keel-server/src/main/java/com/keel/server/insight/CostService.java
keel-server/src/main/java/com/keel/server/insight/QualityService.java
keel-server/src/main/java/com/keel/server/insight/SharedServiceMonitor.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/main/java/com/keel/server/integration/{langfuse,litellm,prometheus}/**
keel-server/src/test/java/com/keel/server/insight/**
docs/specs/p1/P1-14-insight-overview-cost.md
```

`TraceQueryService` 不在本任务改。

## 接口契约

```
GET /api/v1/insight/overview?env&range
GET /api/v1/insight/costs?env&range
GET /api/v1/insight/services
```

`range` 为 `24h`、`7d`、`30d`。

`Overview.kpi` 里本任务要填上 `calls`、`modelCostCny`、`avgScore`。`pendingApprovals` 在 P3-1 之前保持 0，并在代码里留 TODO。

`ModelGateway.models[].priceConfigured`：LiteLLM `model_info` 缺单价时为 false。`keys[].alias` 形如 `{agent}-{env}`。`dailyBudgetCny` 用汇率把美元预算换回人民币，汇率与 P1-8 相同，没有默认值。

## 实现要点

- **花费以 LiteLLM 为准，Langfuse 的费用只做对照日志，不拿来当总览数字。** 对账不上时测试要失败在断言上，而不是取平均值。
- **分页必须拉完 `/spend/logs/v2`。** 只取第一页会在超过一页后少算。汇总可以用 `/global/spend/report`，但按智能体拆分仍要能对上明细之和。
- **美元换人民币用配置汇率。** 缺汇率时成本字段返回 null，接口仍 200，日志说明缺配置。不要把美元数直接标成 `costCny`。
- **`priceConfigured=false` 的模型，花费展示为 0 时必须同时标出未配置。** 这是国内模型静默记 0 的那个坑。
- `QualityService` 读 v3 scores。质量中心页面是 P2-12，本任务只把 `avgScore` 填进总览，并保留一个按智能体取分数的方法给 P2-12。
- `SharedServiceMonitor` 读 Prometheus。共享服务页面也是 P2-12。本任务把 `GET /insight/services` 按 `SharedServices` 填上能查到的指标；Prometheus 不可用时对应数字为 null，不返回假健康。
- insight 只读，不写 `agent` 或其他业务表。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] 假 LiteLLM 两页 spend 明细之和等于 `modelCostCny`（按汇率换算后）
- [ ] 按智能体拆分的成本之和等于总览成本
- [ ] 缺 `input_cost_per_token` 的模型 `priceConfigured=false`
- [ ] 请求里没有发往 `/spend/logs`、`/api/public/metrics`、`/api/public/scores` 的调用
- [ ] `pendingApprovals` 为 0，且源码有 P3-1 的 TODO

## 明确不做

- 不实现链路详情（P1-13）
- 不改控制台，不去掉 mock（P1-15、P2-12）
- 不实现评测实验列表（P2-4、P2-12）
