# R4-4 路由策略、熔断和告警规则

## 目标

薄网关按已发布的路由策略选择模型。超时或 5xx 只切换一次。鉴权失败和预算不足不切换。试算看到的模型顺序和实际调用一致。渠道连续失败后熔断。告警规则出现在总览。

## 依赖

- 前置任务：R4-1。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的总览告警增加 `CHANNEL_FAULT`，模型页增加可选 `routePolicy` 和 `alertRules`。
- 依赖的外部组件及其真实行为：新管理接口先写进技术文档第 15 节和 `.cursor/rules/external-apis.mdc`。它们是薄网关自己的接口，不是厂商接口。

## 改哪些文件

```
docs/specs/r1014/R4-4-route-policy.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
docs/architecture/Keel-技术文档.html
.cursor/rules/external-apis.mdc
contracts/console-api.openapi.yaml
keel-llm/src/main/java/com/keel/llm/RoutePolicy.java
keel-llm/src/main/java/com/keel/llm/Gateway.java
keel-llm/src/main/java/com/keel/llm/LlmController.java
keel-llm/src/test/java/com/keel/llm/GatewayTest.java
keel-llm/src/test/java/com/keel/llm/RoutePolicyTest.java
keel-server/src/main/java/com/keel/server/integration/litellm/LiteLlmClient.java
keel-server/src/main/java/com/keel/server/insight/CostService.java
keel-server/src/main/java/com/keel/server/insight/GatewayAlertRules.java
keel-server/src/main/java/com/keel/server/insight/OverviewService.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/test/java/com/keel/server/insight/GatewayAlertRulesTest.java
console/src/api/schema.d.ts
console/src/api/models.ts
console/src/views/models/ModelGateway.vue
console/src/views/overview/overviewFormat.ts
```

## 接口契约

- `GET/POST /admin/v1/route-policy`，`POST /admin/v1/route-policy/rollback`，`POST /admin/v1/route/preview`。
- `GET/POST /admin/v1/alert-rules`。
- 控制台经 `GET /insight/costs` 读到 `routePolicy` 和 `alertRules`，经 `POST /insight/alert-rules` 新建规则。

## 界面对齐

- 原型位置：`#/models` 路由与降级、告警规则；`#/overview` 告警与事件。
- 对齐清单：运行总览「告警」、模型与成本「路由与降级」「告警规则」。
- 页面数据来自 keel-server。

## 实现要点

- 请求里指定的模型先试。`balanced` 按 fallback 顺序选一个备选；`cost` 选更便宜的备选。只保留一个备选。
- 401、403 不记渠道失败，也不切换。超时和 5xx 记一次失败。同一渠道连续 2 次后熔断 60 秒，期间跳过。
- 预算不足在调用上游之前返回，不会换模型。

## 验收标准

```bash
mvn -o -pl :keel-llm -Dtest=GatewayTest,RoutePolicyTest test
mvn -o -pl :keel-server -Dtest=GatewayAlertRulesTest,CostServiceTest test
cd console && npx vitest related src/views/models/ModelGateway.vue src/views/overview/overviewFormat.ts --run
```

- [x] 上游连续失败时只再调用一个备选模型。
- [x] 上游 401 不切换。
- [x] 发布 cost 策略后，试算的模型顺序和这次实际调用一致；回滚回到 balanced。
- [x] 渠道连续两次失败后，下一次计划跳过该渠道。
- [x] 预算和渠道规则能变成总览告警。

## 明确不做

- 不按上下文长度或工具能力过滤模型。
- 不把延迟测量做成跨副本的统计，`latency` 目标在没有样本时按模型名稳定排序。
