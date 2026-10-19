# 模型与成本页改接真实网关

## 目标

`#/models` 仍用三个 tab（模型与渠道、Key 与配额、路由），数据来自 keel-server 已有的薄网关接口。页面不再放优化稿的演示数字，也不再显示「提案」和「薄网关 keel-llm · 2 副本」。

## 改哪些文件

```
docs/specs/r1018/models-cost-live.md
console/src/views/models/ModelGateway.vue
```

删掉只服务演示的 `modelCost.ts` 和 `modelCost.test.ts`。

## 接口

全部走已有控制台接口，不改契约：

- `GET /insight/costs`：模型、虚拟 Key、账单、路由策略、告警规则
- `POST /insight/models/{name}/probe`
- `POST /insight/costs/keys/{alias}/budget`
- `POST /keys/{alias}/rotate`
- `POST /insight/route-policy`、`POST /insight/route-policy/rollback`、`POST /insight/route/preview`
- `POST /insight/alert-rules`
- `GET /insight/costs/export`

## 界面对齐

版式按优化稿的三块：渠道表、Key 双栏、路由表。不显示「提案」「要做」和「薄网关 keel-llm · 2 副本」。

- 模型与渠道：一行一个模型。渠道名和地址来自提供方，厂商 RPM 用 Key 上的限制，已分配是允许多少把 Key。健康和探测走网关。熔断没有列表，写「未接入」。
- Key 与配额：允许的模型、RPM、TPM、并发来自网关，只读。日预算可以保存。失败请求来自账单。
- 路由：已发布版本和均衡 / 成本优先 / 延迟优先。发布、回滚、试算走薄网关。降级链是每把 Key 的模型列表。

## 明确不做

- 不新增渠道、不按渠道分配 RPM、不模拟 5xx 或 429。薄网关没有这些管理接口。
- 不做逻辑模型权重和回放试算。路由只有 `balanced`、`cost`、`latency`。
