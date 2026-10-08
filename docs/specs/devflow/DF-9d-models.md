# DF-9d 控制台：模型网关的研发成本视图

## 目标

模型网关现有的模型表、虚拟 Key 与预算、路由不变，多一块"研发成本"：按研发智能体（含 meta-agent）汇总本月花费，并列出花费最高的 5 个研发任务；另外提示"编码模型待接入、单价待核实"。

来源：`docs/console/Keel-全流程智能体化控制台.html` 模型网关增强；`docs/design/Keel-全流程智能体化方案.html` 第 09 节成本口径（以薄网关 `costCny` 为准）。

## 依赖

- 前置：现有模型网关（`GET /insight/costs`）。
- 契约：`contracts/console-api.openapi.yaml` 的 `ModelGateway`。
- 外部：研发任务账本（DF-2）还没有，按任务汇总暂时没有真实数据；编码模型的单价必须按厂商当天价目填写，未核实前不写进薄网关配置，也不写进 mock 的模型表。

## 改哪些文件

```
docs/specs/devflow/DF-9d-models.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/models/ModelGateway.vue
console/src/views/models/devflowCost.ts
console/src/views/models/devflowCost.test.ts
console/src/mocks/data/models.ts
```

## 接口契约

`ModelGateway` 加可选字段 `devflowCost`，不升版本：

```yaml
devflowCost:
  totalCny: number
  byAgent: [{ agent: string, layer: enum [META, DEV], costCny: number }]
  topJobs: [{ jobId: string, title: string, spentCny: number, budgetCny: number }]
```

## 实现要点

- 金额以薄网关 `costCny` 为准，保留一位小数。按研发智能体的条形图按金额从高到低排列，每条宽度 = 该项 / 总额。写成 `devflowCost.ts` 纯函数并测试（含总额为 0 时不除零）。
- 任务行链到 `/jobs/{jobId}`，显示"花费 / 预算"，花费超过预算 80% 标红。
- 没有 `devflowCost` 时，这块显示"研发任务账本接入后显示（DF-2）"，不显示 0。
- "编码模型"提示为静态文案："编码模型（待定）· 单价待核实 · 缺单价时薄网关拒绝启动"。不往模型表里加假行。
- 现有的模型表、Key 预算调整、路由表都不改。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/models/devflowCost.test.ts
```

- [ ] 没有 `devflowCost` 时，页面与原来一致，只多一块占位说明
- [ ] 有数据时，条形宽度与金额成比例，任务行能跳到任务详情
- [ ] 模型表里没有新增的假模型

## 明确不做

- keel-server 计算研发成本（需要研发任务账本和智能体层级，归 DF-2）
- 在薄网关配置里加编码模型
