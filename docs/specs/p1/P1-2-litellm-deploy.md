# P1-2 LiteLLM 部署

## 目标

LiteLLM 两个副本加 Redis 在内网可用。`config.yaml` 里写了模型、单价和降级路由。国内模型调用能记到非零成本。自带的 Langfuse 回调是关的。

## 依赖

- 前置任务：P1-1（Langfuse 项目已在，价格口径要对齐；LiteLLM 本身不把追踪打到 Langfuse）
- 依赖的契约文件：无。模型名以各智能体 manifest 的 `spec.models` 为准，首批至少包含技术文档示例里的 `qwen-plus`、`deepseek-v3`
- 依赖的外部组件及其真实行为：`.cursor/rules/external-apis.mdc` 的 LiteLLM 一节。`/key/delete` 部署当天打开该实例的 `/docs` 确认，确认前不要写进回收流程

## 改哪些文件

```
deploy/litellm/**
deploy/k3s/litellm/**
docs/specs/p1/P1-2-litellm-deploy.md
```

厂商 API Key 只放 LiteLLM 的 Secret，不进 git，不进智能体仓库。

## 接口契约

本任务把这些接口部署到可调用，供 P1-8、P1-14 使用：

```
POST /key/generate
POST /key/update
POST /key/block
POST /key/unblock
GET  /key/info
GET  /spend/logs/v2
GET  /global/spend/report
```

不要把客户端指向 `GET /spend/logs`。它最多返回最近 1 万行。

`litellm_settings.key_alias_pattern` 强制 `{agent}-{env}`。

## 实现要点

- **每个 Pod：1 vCPU + 4Gi，requests = limits。** 低于 4Gi 会 OOM。每个 Pod 1 个 worker。多副本必须接 Redis。按 CPU 60% 扩容。
- **关掉 LiteLLM 自带的 Langfuse 回调。** generation span 只由 Keel SDK 上报一次，回调开着会记两条。
- **国内模型必须在 `model_info` 里写 `input_cost_per_token` 和 `output_cost_per_token`。** 缺了只打一行 WARNING，成本记 0。同一组价格写进 P1-1 的 Langfuse Model Definitions。
- 预算字段是美元。manifest 里的 `budget.dailyCny` 不在本任务换算；换算发生在 P1-8。这里只保证 LiteLLM 收到的 `max_budget` 单位是美元。
- 管理界面不对公网。厂商 Key 不出现在 `config.yaml` 的提交内容里，用 Secret 引用。
- 部署当天在 `/docs` 查 `/key/delete`。没有这个接口就在本文件写明「下线只做 block」，不要猜。

## 验收标准

```bash
docker compose -f deploy/litellm/docker-compose.yml config   # 或 kubectl apply --dry-run=client
# 对一个已配单价的国内模型发一次 chat completion（Key 用部署时生成的探测 Key，不进仓库）
curl -s "$LITELLM_HOST/global/spend/report" | jq .
```

- [ ] 探测调用在 `/spend/logs/v2` 里的 cost 大于 0
- [ ] 配置里搜不到 Langfuse callback / success_callback 指向 Langfuse
- [ ] 两个副本同时在，且都连到同一个 Redis
- [ ] Pod 的 requests 与 limits 都是 cpu `1`、memory `4Gi`
- [ ] 别名不符合 `{agent}-{env}` 的 `/key/generate` 被拒绝
- [ ] `/key/delete` 的核实结论写回本文件

## 明确不做

- 不实现 `LiteLlmProvisioner`（P1-8）
- 不在 keel-server 里聚合花费（P1-14）
- 不把人民币数字直接写进 `max_budget`
