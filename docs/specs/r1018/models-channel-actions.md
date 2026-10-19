# 模型页切换渠道和按渠道熔断

## 目标

`#/models` 的「模型与渠道」与 `docs/console/Keel-优化效果-20261018.html` 一样，有「切换 prod 渠道」「新增渠道」和「模拟 2 次 5xx」。这三项打到薄网关，熔断按渠道计，60 秒后半开，探测成功后恢复。

目录里的模型仍是一条内置渠道，走原来的上游和厂商密钥。新增渠道不接收密钥，调用仍用该模型在 Secret `keel-llm-vendors` 里的密钥，但熔断单独计算。同一上游地址的另一个渠道不受影响。

## 改哪些文件

```
docs/specs/r1018/models-channel-actions.md
docs/specs/r1018/models-cost-live.md
docs/specs/r1014/ui-checklist.md
docs/architecture/Keel-技术文档.html
.cursor/rules/external-apis.mdc
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/api/models.ts
console/src/views/models/channelBreaker.ts
console/src/views/models/channelBreaker.test.ts
console/src/views/models/ModelGateway.vue
keel-llm/src/main/java/com/keel/llm/RoutePolicy.java
keel-llm/src/main/java/com/keel/llm/ChannelBoard.java
keel-llm/src/main/java/com/keel/llm/Gateway.java
keel-llm/src/main/java/com/keel/llm/LlmController.java
keel-llm/src/main/java/com/keel/llm/LlmConfiguration.java
keel-llm/src/main/java/com/keel/llm/ModelCatalog.java
keel-llm/src/test/java/com/keel/llm/RoutePolicyTest.java
keel-llm/src/test/java/com/keel/llm/GatewayTest.java
keel-server/src/main/java/com/keel/server/integration/litellm/LiteLlmClient.java
keel-server/src/main/java/com/keel/server/insight/CostService.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
```

## 接口

薄网关：

- `GET /admin/v1/channels`：内置渠道加后来新增的渠道。每行有 `breaker`（`closed` / `open` / `half_open`）和 `openUntil`。
- `POST /admin/v1/channels` body `{name, model, env, rpm}`。`model` 必须是已配置模型。不接受厂商密钥。
- `POST /admin/v1/channels/switch` body `{id}`。只把别名以 `-prod` 结尾、且允许该千问模型的 Key 改绑到目标渠道。
- `POST /admin/v1/channels/simulate` body `{id}`。记两次失败，熔断 60 秒。不请求厂商。
- `POST /admin/v1/channels/probe` body `{id}`。向该模型的上游发一次探测，成功则关闭熔断。

控制台经 keel-server：`GET /insight/costs` 带上 `channels`；写操作是 `POST /insight/channels`、`/insight/channels/switch`、`/insight/channels/simulate`、`/insight/channels/probe`。只允许平台管理员。

## 界面对齐

- 渠道表右侧是「切换 prod 渠道」和「+ 新增渠道」。
- 熔断列：正常、熔断中（倒计时）、「模拟 2 次 5xx」、半开后「放行探测」。
- 只有一个 prod 千问渠道时，切换对话框说明要先新增。

## 验收标准

```bash
mvn -o -pl :keel-llm -Dtest=GatewayTest,RoutePolicyTest test
mvn -o -pl :keel-server -Dtest=CostServiceTest test
cd console && npx vitest related src/views/models/channelBreaker.ts src/views/models/ModelGateway.vue --run
```

- [ ] 模拟两次失败后该渠道为熔断，60 秒后为半开，探测成功后关闭。
- [ ] 切到新增渠道后，这个渠道熔断不会挡住仍走内置渠道的 Key。
- [ ] 页面能切换、新增和模拟，数据来自 keel-server。

## 明确不做

- 不在页面或接口里提交厂商密钥。
- 不按渠道重算 RPM 份额，也不模拟 429。
- 不改逻辑模型权重和回放试算。
