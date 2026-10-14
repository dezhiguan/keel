# R0-1 虚拟 Key 轮换与旧版本失效

## 目标

同别名重建虚拟 Key 后，旧 Key 立即且永久返回 401 `LLM_KEY_BLOCKED`；控制台显示版本，并能轮换、一次性查看新 Key。

## 依赖

- 前置任务：无。
- 依赖的契约文件：`contracts/error-codes.yaml` 已有 `LLM_KEY_BLOCKED`；`contracts/console-api.openapi.yaml` 的 `ModelGateway.keys` 先增加可选版本字段与轮换响应。
- 依赖的外部组件及其真实行为：薄网关现有 `POST /admin/v1/keys` 同别名重建，`GET /admin/v1/keys` 列表；Kubernetes Secret `keel-{agent}` 由 Keel 写入。轮换只使用仓库内薄网关实现，不推测 Langfuse 或 auth-gateway 接口。

## 改哪些文件

```
docs/specs/r1014/R0-1-key-rotation.md
docs/specs/r1014/ui-checklist.md
docs/specs/r1014/README.md
contracts/console-api.openapi.yaml
keel-llm/src/main/java/com/keel/llm/{KeyDirectory,Gateway,LlmController}.java
keel-llm/src/test/java/com/keel/llm/**
keel-server/src/main/java/com/keel/server/integration/litellm/LiteLlmClient.java
keel-server/src/main/java/com/keel/server/insight/{CostService,controller/InsightController}.java
keel-server/src/main/java/com/keel/server/provisioning/SecretWriter.java
keel-server/src/main/java/com/keel/server/provisioning/KeyRotationService.java
keel-server/src/main/java/com/keel/server/provisioning/KeyRotationController.java
keel-server/src/test/java/com/keel/server/{insight,provisioning}/**
console/src/api/{models.ts,schema.d.ts}
console/src/views/models/ModelGateway.vue
console/src/views/models/*.test.ts
```

## 接口契约

- `POST /admin/v1/keys` 仍只返回一次明文 `key`，新增 `version`。同别名每重建一次版本加一。`GET /admin/v1/keys` 和 `GET /admin/v1/keys/{alias}` 新增整数 `version`，不返回 token。
- `GET /api/v1/insight/costs` 的 `keys[].version` 为可选正整数。
- `POST /api/v1/keys/{alias}/rotate` 返回 `{alias, key, version}`。新 Key 明文仅在该响应出现。服务端沿用当前模型、降级和预算配置，把新 Key 写入该智能体的 Secret，并触发已有 Deployment 重启，让环境变量生效。不存在可更新的 Secret 或 Deployment 时拒绝轮换，避免创建无法使用的新 Key。
- 调用端带旧 Key 请求 `/v1/chat/completions` 或 `/v1/embeddings`：HTTP 401，统一错误体的 `code=LLM_KEY_BLOCKED`。

## 界面对齐

- 原型位置：`#/models` → “Key 与配额” tab，“Key 别名 · 版本”列和“轮换”按钮。
- 对齐清单：`docs/specs/r1014/ui-checklist.md` 的“模型与成本 → Key 与配额 → Key 版本与轮换”行。
- 页面数据：`GET /api/v1/insight/costs`；轮换经 keel-server 的 `POST /api/v1/keys/{alias}/rotate`，控制台不直连薄网关。

## 实现要点

- `KeyDirectory.byToken` 必须同时比对索引命中的别名和当前别名记录中的 token；旧 token 索引可以保留，但绝不能重新生效。新版本持久化到 Redis；旧记录缺 `version` 时视为 v1。
- 同别名重建的版本递增在共享存储中原子完成，防止两个副本签发相同版本。更新预算或封禁不能用过期快照覆盖新 token。
- 轮换先确认 Secret 与 Deployment 存在；网关切换后更新 Secret，再重启 Deployment。失败必须明确报错，不宣称轮换成功。明文不得写日志、审计 payload 或列表接口。
- 控制台仅在轮换响应中显示一次明文；关闭后无法从列表重新取得。

## 验收标准

```bash
mvn -o -pl :keel-llm test
mvn -o -pl :keel-server test
cd console && npx vitest related src/views/models/ModelGateway.vue src/api/models.ts
```

- [x] 旧 Key 在同别名重建后及重建网关实例后均返回 401 `LLM_KEY_BLOCKED`，新 Key 可调用。
- [x] 两个副本共享 Key 存储时，旧版本仍失效，版本递增。
- [x] Key 列表与控制台展示版本，不泄露明文；轮换响应仅显示新明文一次。
- [x] 轮换把新 Key 写入现有 Secret，并促使读取环境变量的实例重启。

## 明确不做

- 不改预算预留与结算（R0-2），不改网关路由策略（R4-4）。
- 不对线上环境发起轮换或部署。
