# P1-8 薄网关、Langfuse 与 Secret 开通

## 目标

注册流程能在薄网关创建别名为 `{agent}-{env}` 的虚拟 Key，日预算直接使用 manifest 里的人民币数额，把密钥写进 `keel-{agent}` Secret，并把评测用例导入该环境的 Langfuse 数据集。

2026-10-04 之前曾按 LiteLLM 的 `/key/generate` 和美元 `max_budget` 写过一版。本 spec 生效后那版作废：客户端改打 P1-2 的管理接口，不再做汇率换算。

## 依赖

- 前置任务：P1-6、P1-1、P1-2
- 依赖的契约文件：`contracts/manifest.schema.json` 的 `spec.models.budget.dailyCny`、`spec.eval`
- 依赖的外部组件：
  - 薄网关：`POST /admin/v1/keys`、`POST /admin/v1/keys/{alias}/block`。见 P1-2
  - Langfuse Cloud 日本节点：`/api/public/datasets`、`/api/public/dataset-items`。不调用 `/api/public/dataset-run-items`，不调用 `/api/public/traces`
  - Kubernetes Secret：fabric8，命名空间 `agents`

## 改哪些文件

```
keel-server/src/main/java/com/keel/server/provisioning/LiteLlmProvisioner.java
keel-server/src/main/java/com/keel/server/provisioning/LangfuseProvisioner.java
keel-server/src/main/java/com/keel/server/provisioning/SecretWriter.java
keel-server/src/main/java/com/keel/server/integration/litellm/**
keel-server/src/main/java/com/keel/server/integration/langfuse/**
keel-server/src/main/java/com/keel/server/integration/k8s/**
keel-server/src/test/java/com/keel/server/provisioning/**
docs/specs/p1/P1-8-external-provisioners.md
```

`agent_resource.type` 继续用已经落库的 `litellm_key`。这个枚举值表示薄网关虚拟 Key，本任务不改 CHECK，不新增类型。

## 接口契约

虚拟 Key：

```
POST /admin/v1/keys
alias={agent}-{env}, models, fallback, dailyBudgetCny, allowFallback
```

向量化用途的 Key 设 `allowFallback=false`。其余 Key 的 `fallback` 来自 manifest，`dailyBudgetCny` 等于 `budget.dailyCny`。

Secret `keel-{name}` 的键：

```
KEEL_CLIENT_PRIVATE_KEY
KEEL_LLM_KEY
KEEL_LLM_BASE_URL
LANGFUSE_PUBLIC_KEY
LANGFUSE_SECRET_KEY
LANGFUSE_HOST
```

`LANGFUSE_HOST` 为 `https://jp.cloud.langfuse.com`。Langfuse 的项目 Key 是该环境共用的，不是每个智能体一对。数据集名称用 manifest 的 `spec.eval.dataset`。

## 实现要点

- **`dailyBudgetCny` 原样下发。** 不读取汇率，不把 30 元换成美元。
- **别名格式只接受 `{agent}-{env}`。** 回收调用 `POST /admin/v1/keys/{alias}/block`。
- **开通请求里不要让网关上报 Langfuse。** 网关本身没有这条回调。
- **Langfuse 用该环境项目的 Basic Auth，主机是日本节点。** 导入的是 `evals/seed.jsonl` 的条目。重复导入同一条要幂等，注册重试不能把用例翻倍。
- **Secret 里不放厂商 Key。** `KEEL_LLM_KEY` 是虚拟 Key。日志和 `agent_resource.external_id` 可以记 key 别名，不要记 key 明文。
- 测试用假的薄网关和 Langfuse HTTP 服务，不要 mock 掉整个 client 类。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] `dailyCny=30` 时，假网关收到的 `dailyBudgetCny` 是 30，请求体里没有美元金额，也没有汇率
- [ ] `key` 别名为 `{agent}-{env}`，revoke 调用的是 `/admin/v1/keys/{alias}/block`
- [ ] Secret 的六个键都在，`LANGFUSE_HOST` 是日本节点，值里没有厂商 Key 字样
- [ ] 同一数据集导入两次，假 Langfuse 里的条目数不翻倍
- [ ] 假服务返回 500 时，异常抛给 P1-6 的编排，本类不吞掉

## 明确不做

- 不改开通顺序和回滚顺序（P1-6）
- 不打提示词 production 标签（P2-11，`PATCH /api/public/v2/prompts/{name}/versions/{version}`）
- 不读花费报表（P1-14）
- 不改 `agent_resource` 的类型枚举
