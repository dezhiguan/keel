# R0-3 usage 缺失进入待计费并告警

## 目标

薄网关收到成功响应但没有有效 usage 时保留预算预留额，记录待计费账单；控制台显示待计费数量、账单状态和告警。

## 依赖

- 前置任务：R0-2 原子预留与幂等结算。
- 依赖的契约文件：先扩展 `contracts/console-api.openapi.yaml` 的可选待计费字段。
- 依赖的外部组件及其真实行为：沿用 `.cursor/rules/external-apis.mdc` 已列明的薄网关 `GET /admin/v1/spend`，不新增外部调用。

## 改哪些文件

```
docs/specs/r1014/R0-3-pending-usage.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
keel-llm/src/main/java/com/keel/llm/{BudgetCounter,Gateway,LlmController,SpendLog}.java
keel-llm/src/test/java/com/keel/llm/{BudgetCounterTest,GatewayTest}.java
keel-server/src/main/java/com/keel/server/integration/litellm/LiteLlmClient.java
keel-server/src/main/java/com/keel/server/insight/{CostService,OverviewService,controller/InsightController}.java
keel-server/src/test/java/com/keel/server/insight/{CostServiceTest,OverviewServiceTest}.java
console/src/api/schema.d.ts
console/src/views/models/ModelGateway.vue
console/src/views/overview/OverviewView.vue
console/src/views/models/*.test.ts
console/src/views/overview/*.test.ts
```

## 接口契约

- 薄网关 `GET /admin/v1/spend` 每行增加 `status: SETTLED|PENDING|FAILED`；待计费行 `costCny: null`，附 `reservedCny` 和 `requestId`。
- 控制台 `GET /insight/costs` 增加可选 `pendingCount` 与 `bills[]`，每行含时间、requestId、alias、model、token、费用、预留和状态；总费用只累计已结算。
- `GET /insight/overview` 的 `kpi.pendingModelBills` 为可选整数；`alerts[]` 按智能体汇总 usage 缺失的待计费行。

## 界面对齐

- 原型位置：`#/overview` 模型成本卡片“待计费 N 条”及“告警与事件”；`#/models` 的“账单” tab。
- 对齐清单：`ui-checklist.md` 的“运行总览 → 指标口径”与“模型与成本 → 账单”两行。后续 R4-1 再增加逐尝试明细、稳定游标和导出。
- 页面数据：分别来自 keel-server 的 `GET /insight/overview` 与 `GET /insight/costs`；控制台不直连薄网关。

## 实现要点

- 成功响应必须同时有非负整数 `prompt_tokens`、`completion_tokens` 才能结算。缺字段、null、负数、字符串等均进入 `PENDING`；合法的两个 0 仍可结算为 0。
- 待计费保留原预留额，预算继续按 `settled + reserved` 限流；Redis 预留行改为 `PENDING` 后不受孤儿预留清理影响。账单费用用 null，不把未知费用记 0。Redis 独立哈希保存待计费账单，近期列表裁剪时仍可查询。
- `markPending` 幂等。上游失败仍释放预留；仅成功但 usage 无效时进入待计费。服务端只对最近窗口、当前环境的待计费账单计数与告警。
- 本任务沿用 Redis 近期账单，长期持久化、手动对账和游标分页在 R4-1 完成。

## 验收标准

```bash
mvn -o -pl :keel-llm test
mvn -o -pl :keel-server test
cd console && npx vitest related src/views/models/ModelGateway.vue src/views/overview/OverviewView.vue --run
```

- [x] 模拟上游成功但无 usage，账单状态 `PENDING` 且费用为 null、预留仍占预算。
- [x] 同一 requestId 重复标记待计费不增加占用，失败请求仍释放预留。
- [x] 总览出现待计费条数与告警，账单 tab 显示待计费行。

## 明确不做

- 不做 R4-1 的 PostgreSQL 长期账单、逐尝试行、稳定游标或 CSV 导出。
