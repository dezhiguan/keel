# P1-8 LiteLLM、Langfuse 与 Secret 开通

## 目标

注册流程能创建别名为 `{agent}-{env}` 的 LiteLLM 虚拟 Key，把人民币日预算换成美元，把密钥写进 `keel-{agent}` Secret，并把评测用例导入该环境的 Langfuse 数据集。

## 依赖

- 前置任务：P1-6、P1-1、P1-2
- 依赖的契约文件：`contracts/manifest.schema.json` 的 `spec.models.budget.dailyCny`、`spec.eval`
- 依赖的外部组件：
  - LiteLLM：`/key/generate`、`/key/update`、`/key/block`。不用 `/spend/logs`。`/key/delete` 以 P1-2 写回的核实结论为准，未确认就只 block
  - Langfuse：`/api/public/datasets`、`/api/public/dataset-items`。不调用 v3 的 `/api/public/dataset-run-items`
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

在 P0-4 留下的空类上实现。

## 接口契约

虚拟 Key：

```
POST /key/generate
models, max_budget, budget_duration=1d, key_alias={agent}-{env}, metadata.agent
```

Secret `keel-{name}` 的键：

```
KEEL_CLIENT_PRIVATE_KEY
KEEL_LLM_KEY
KEEL_LLM_BASE_URL
LANGFUSE_PUBLIC_KEY
LANGFUSE_SECRET_KEY
LANGFUSE_HOST
```

Langfuse 的项目 Key 是该环境共用的，不是每个智能体一对。数据集名称用 manifest 的 `spec.eval.dataset`。

## 实现要点

- **`dailyCny` 除以配置的汇率才是 `max_budget`。** 汇率来自环境变量，不在代码里写 7.2 这类默认值。汇率缺失则开通失败。
- **别名格式只接受 `{agent}-{env}`。** 与 LiteLLM 的 `key_alias_pattern` 一致。回收先 `POST /key/block`。只有 P1-2 确认存在 `/key/delete` 时才在「确认无流量」之后调用；本任务的回收路径默认 block。
- **关掉任何会让 LiteLLM 再报一份 Langfuse 的配置。** 本任务创建的 Key 也不要打开 Langfuse callback。
- **Langfuse 用该环境项目的 Basic Auth。** 导入的是 `evals/seed.jsonl` 的条目。重复导入同一条要幂等，注册重试不能把用例翻倍。
- **Secret 里不放厂商 Key。** `KEEL_LLM_KEY` 是虚拟 Key。日志和 `agent_resource.external_id` 可以记 key 别名，不要记 key 明文。
- 测试用假的 LiteLLM、Langfuse HTTP 服务，不要 mock 掉整个 client 类。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] `dailyCny=30` 且汇率为 7 时，假 LiteLLM 收到的 `max_budget` 是美元数额而不是 30
- [ ] 汇率未配置时开通失败
- [ ] `key_alias` 为 `{agent}-{env}`，revoke 调用的是 `/key/block`
- [ ] Secret 的六个键都在，值里没有厂商 Key 字样
- [ ] 同一数据集导入两次，假 Langfuse 里的条目数不翻倍
- [ ] 假服务返回 500 时，异常抛给 P1-6 的编排，本类不吞掉

## 明确不做

- 不改开通顺序和回滚顺序（P1-6）
- 不打提示词 production 标签（P2-11，`PATCH /api/public/v2/prompts/{name}/versions/{version}`）
- 不读花费报表（P1-14）
