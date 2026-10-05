# P1-2 自研薄网关

## 目标

`keel-llm` 以两个副本跑在现有应用节点上。智能体用 OpenAI 兼容接口调模型，手里只有虚拟 Key。日预算按人民币计，超时或 5xx 时按该 Key 的降级列表切一次。厂商密钥只在网关的 Secret 里。网关不向 Langfuse 上报。

## 依赖

- 前置任务：无。价格口径与 P1-1 的 Langfuse Model Definitions 对齐，部署顺序可以并行。
- 依赖的契约文件：模型名以 manifest 的 `spec.models` 为准，首批至少包含 `qwen-plus`、`deepseek-v3`。实现时把 `LLM_BUDGET_EXCEEDED` 登记进 `contracts/error-codes.yaml`。
- 依赖的外部组件：厂商的 OpenAI 兼容 HTTP 接口。不要引入 LiteLLM。

## 改哪些文件

```
keel-llm/**
deploy/k3s/keel-llm/**
docs/specs/p1/P1-2-model-gateway.md
```

`deploy/litellm/` 与 `deploy/k3s/litellm/` 不再部署。本任务收掉这两处，或在目录说明里标明作废。厂商 API Key 只放 Secret `keel-llm-vendors`，不进 git，不进智能体仓库。

## 接口契约

调用面（虚拟 Key，`Authorization: Bearer`）：

```
POST /v1/chat/completions
POST /v1/embeddings
```

管理面（仅 keel-server，`Authorization: Bearer` 管理密钥）：

```
POST /admin/v1/keys
POST /admin/v1/keys/{alias}/block
GET  /admin/v1/keys/{alias}
GET  /admin/v1/spend?from&to&alias&page&size
GET  /admin/v1/models
```

`POST /admin/v1/keys` 的正文：

```
alias, models, fallback, dailyBudgetCny, allowFallback
```

`alias` 必须匹配 `^[a-z][a-z0-9-]{1,38}[a-z0-9]-(dev|test|staging|prod)$`。响应里的 `key` 只返回这一次。

`GET /admin/v1/spend` 必须能分页拉完。一条明细至少含 `alias`、`model`、`inputTokens`、`outputTokens`、`costCny`、`requestId`、`ts`。

## 实现要点

- **预算单位是人民币，与 `budget.dailyCny` 相同。** 不做美元换算。计数放 Redis，按 Asia/Shanghai 的自然日清零，两个副本共用这一份计数。达到日预算返回 HTTP 429，错误码 `LLM_BUDGET_EXCEEDED`。
- **缺单价的模型拒绝启动。** 单价写在网关配置里，单位与花费同为人民币。不允许调用成功后把成本记成 0。
- **降级只发生在超时或 5xx，且只试 fallback 列表里的下一个。** `allowFallback=false` 时忽略 fallback。向量化 Key 必须 `allowFallback=false`。
- **不实现 `/rerank`。** 自部署 reranker 不经过本网关。
- **不向 Langfuse 发送任何 span。** generation 只由 Keel SDK 上报一次。
- **资源：** 2 副本，requests `100m` / `256Mi`，limits `500m` / `512Mi`。PostgreSQL 使用现有实例上的库 `keel_llm`。放在现有应用节点，不为它另买机器。
- 下线只做 `POST /admin/v1/keys/{alias}/block`。
- 管理端口不对公网。

## 验收标准

```bash
# 在 keel-llm 模块
mvn -o -pl :keel-llm test
```

- [ ] 已配单价的国内模型，一次 chat completion 在 spend 里的 `costCny` 大于 0
- [ ] 配置缺单价时进程起不来
- [ ] 主模型超时后，带 fallback 的 Key 改用列表中的下一个模型，且只切一次
- [ ] `allowFallback=false` 的 Key 超时后不切换模型
- [ ] 别名不符合 `{agent}-{env}` 的创建请求被拒绝
- [ ] 日预算用尽返回 429，错误码来自 `error-codes.yaml`
- [ ] 两个副本共用 Redis 里的同一日预算计数
- [ ] 网关代码和配置里没有 Langfuse 上报

## 明确不做

- 不实现开通客户端（P1-8）
- 不在 keel-server 里聚合花费（P1-14）
- 不把人民币换算成美元
