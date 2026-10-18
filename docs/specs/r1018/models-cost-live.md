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

- 模型与渠道：模型目录、单价、调用、超时率、成本、健康。未探测的模型可以探测。下面是当前窗口账单，可导出。
- Key 与配额：日预算、RPM / TPM、并发来自网关。点一行后只能改日预算；平台管理员可以轮换。
- 路由：策略是均衡、成本优先、延迟优先。发布、回滚、试算走薄网关。熔断没有列表接口，单元格写「未接入」。

## 明确不做

- 不新增渠道、不按渠道分配 RPM、不模拟 5xx 或 429。薄网关没有这些管理接口。
- 不做逻辑模型权重和回放试算。路由只有 `balanced`、`cost`、`latency`。
