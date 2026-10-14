# R0-2 原子预留与幂等结算

## 目标

并发模型调用在 Redis 中先预留额度，响应后按实际 usage 只结算一次并退回差额；跨副本日预算不超限。

## 依赖

- 前置任务：无；承接 R0-1 的版本化 Key。
- 依赖的契约文件：`contracts/error-codes.yaml` 已有 `LLM_BUDGET_EXCEEDED`；`contracts/console-api.openapi.yaml` 的 `ModelGateway.keys` 先加可选 `reservedCny`。
- 依赖的外部组件及其真实行为：Redis 支持原子 Lua 脚本；薄网关上游使用现有 OpenAI 兼容 `/v1/chat/completions`、`/v1/embeddings`。不依赖 Langfuse 或 auth-gateway 新接口。

## 改哪些文件

```
docs/specs/r1014/R0-2-atomic-budget.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
keel-llm/src/main/java/com/keel/llm/{BudgetCounter,Gateway,LlmController,SpendLog,ModelCatalog,LlmConfiguration}.java
keel-llm/src/main/resources/application.yaml
keel-llm/src/test/java/com/keel/llm/**
keel-server/src/main/java/com/keel/server/integration/litellm/LiteLlmClient.java
keel-server/src/main/java/com/keel/server/insight/{CostService,controller/InsightController}.java
keel-server/src/test/java/com/keel/server/insight/**
console/src/api/schema.d.ts
console/src/views/models/ModelGateway.vue
console/src/views/models/*.test.ts
```

## 接口契约

- 调用接口、请求格式不变；网关为一次请求生成一个内部 `requestId`，重试尝试和最终账单共用。
- `BudgetCounter.reserve(alias, requestId, maxCostCny, dailyBudgetCny)` 原子比较 `settled + reserved + maxCost <= dailyBudget`；重复 requestId 不重复预留。
- `BudgetCounter.settle(alias, requestId, actualCostCny)` 原子把预留移到已结算并退回差额；重复 requestId 返回首次结果，不再扣费。失败或上游无可计费结果时 `release` 预留。
- `GET /admin/v1/keys` 增加 `reservedCny`；`GET /api/v1/insight/costs` 的 `keys[].reservedCny` 可选，控制台显示“已结算 + 预留 / 日预算”。

## 界面对齐

- 原型位置：`#/models` → “Key 与配额” tab，“已结算 + 预留 / 日预算”列。
- 对齐清单：`docs/specs/r1014/ui-checklist.md` 的“模型与成本 → Key 与配额 → 已结算 + 预留 / 日预算”行。
- 页面数据：`GET /api/v1/insight/costs`，由 keel-server 从网关管理接口读取；控制台不直连薄网关。

## 实现要点

- 每个模型显式配置提供商的最大输入与最大输出 token。预留按完整模型输入上限与输出上限计算；只在有明确完整输出上限的 `max_completion_tokens` 且该模型支持时才缩小输出预留。`max_tokens` 对部分思考模型仅限制最终答复，不能据此缩小预留。降级候选按最高总费用预留。估算值向上取到人民币微元。模型边界配置缺失时拒绝启动，避免按任意默认值低估。
- 模型边界依据阿里云模型文档（qwen3.8-flash、qwen-plus）；其余模型采用保守配置，实际供应商上限仍需部署前核实。单价仍遵循现有配置规则，价格版本与分段计费属于 R4-1。
- Redis Lua 一次完成比较与预留、结算与退款、失败释放；本地实现保持相同状态机供不启 Redis 的测试使用。账目按 Asia/Shanghai 自然日分桶。
- Redis 使用 `keel-llm:budget:v2:` 哈希键；脚本首次访问时把旧字符串键的当日已支出迁为 `settled`，避免升级后键类型冲突或丢掉当天已花额度。
- 预留设超时清理窗口，超过请求超时与宽限期的孤儿预留在后续读写时原子回收；已结算 requestId 保留到跨日后，避免重复扣费。
- 上游最终失败时释放预留；同一请求的每次重试共用 requestId。usage 缺失的待计费处理留给 R0-3，本任务只防止并发超额与重复扣费。
- 金额计算用整数微元；不以浮点数决定准入。预算值采用向下取整，预留采用向上取整。

## 验收标准

```bash
mvn -o -pl :keel-llm test
mvn -o -pl :keel-server test
cd console && npx vitest related src/views/models/ModelGateway.vue --run
```

- [x] 50 个并发请求在只够约 10 次预留的预算下，不突破日预算。
- [x] 同一 requestId 重复结算不重复扣费，预留差额退回。
- [ ] 两个网关副本共用 Redis 账目；孤儿预留可回收。当前本机缺少 Docker，Redis 实例集成测试待具备环境时补跑；本地双 Gateway 共用账目测试已覆盖状态机。
- [x] 控制台显示已结算、预留和日预算三项真实数据。

## 明确不做

- 不把账单迁到 PostgreSQL（R4-1）；usage 缺失的待计费与告警由 R0-3 处理。
