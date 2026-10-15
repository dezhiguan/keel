# R4-1 薄网关账单、流式结算与限流

## 目标

薄网关把每次模型尝试写成一行账单，能按稳定游标拉全量并与日预算对账；流式响应在客户端断开时按已收到的 usage 结算；每把虚拟 Key 可以限制每分钟请求数、每分钟 token 和并发。

## 依赖

- 前置任务：阶段 0（R0-2 预留结算、R0-3 待计费）。
- 依赖的契约文件：`contracts/error-codes.yaml` 增加 `LLM_RATE_LIMITED`；`contracts/console-api.openapi.yaml` 为账单、模型和 Key 增加可选字段，并增加账单导出。
- 依赖的外部组件及其真实行为：不新增 Langfuse 或 auth-gateway 调用。账单仍由 keel-server 读薄网关 `GET /admin/v1/spend`，控制台不直连薄网关。单价仍以网关配置为准，本任务不改「缺单价拒绝启动」。

## 改哪些文件

```
docs/specs/r1014/R4-1-llm-spend.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
.cursor/rules/external-apis.mdc
contracts/error-codes.yaml
contracts/console-api.openapi.yaml
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
sdk-python/keel/_generated/errors.py
sdk-python/keel/llm/client.py
keel-llm/pom.xml
keel-llm/src/main/resources/application.yaml
keel-llm/src/main/resources/db/migration/V1__llm_spend.sql
keel-llm/src/main/java/com/keel/llm/**
keel-llm/src/test/java/com/keel/llm/**
keel-server/src/main/java/com/keel/server/integration/litellm/LiteLlmClient.java
keel-server/src/main/java/com/keel/server/insight/CostService.java
keel-server/src/main/java/com/keel/server/insight/TraceQueryService.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
deploy/k3s/services/keel-llm.yaml
console/src/api/schema.d.ts
console/src/api/http.ts
console/src/api/models.ts
console/src/views/models/modelBill.ts
console/src/views/models/modelBill.test.ts
console/src/views/models/ModelGateway.vue
console/src/views/trace/TraceDetail.vue
```

## 接口契约

- `GET /admin/v1/spend?limit=&cursor=`。缺省 `limit=50`。按 `created_at`、`attemptId` 从新到旧。`nextCursor` 为空表示没有下一页。插入更新的行不会挤动已经翻过的游标。
- 每一行是一次尝试：`attemptId`、`requestId`、`traceId`、`priceVersion`、`routeVersion`（`primary` 或 `fallback`）、`status`（`SETTLED`、`PENDING`、`FAILED`）。
- `POST /admin/v1/keys` 可选 `rpm`、`tpm`、`concurrency`。缺省不限制。超限返回 `LLM_RATE_LIMITED`。
- `stream: true` 时响应为 SSE。客户端断开且已经解析到 usage 时按该 usage 结算；没有 usage 时记 `PENDING`，费用不是 0。
- 配置了 `KEEL_LLM_JDBC_URL` 时，同一行写入 `keel_llm.llm_spend`，游标读这张表。没配置时游标仍读现有近期账单。
- 控制台 `GET /insight/costs` 的账单行带上上述字段，`nextCursor` 可选。`GET /insight/costs/export` 返回 CSV。模型行带 `priceVersion`、`cacheCnyPerToken`、`health`（`CONFIGURED` 或 `LIVE`）、`drift`。Key 行带 `rpm`、`tpm`、`concurrency`。
- 链路详情里带 `model` 的节点增加可选 `attempts`。

## 界面对齐

- 原型位置：`#/models` 的模型、Key、账单；`#/traces/:id` 模型调用节点。tab 结构仍留给 R4-3，本任务在现有三块上补字段。
- 对齐清单：调用链路「详情 · 模型调用节点」；模型与成本「模型与渠道」「Key 与配额 · RPM / TPM、并发」「账单」。
- 页面数据来自 `GET /insight/costs`、`GET /insight/costs/export`、`GET /insight/traces/{id}`。

## 实现要点

- 价格版本缺省是输入价、输出价、缓存价拼出来的字符串。配置了 `price-version` 时用配置值。缓存价缺省时，缓存 token 按输入价计。缓存价写成 0 或负数，启动失败。
- 向量模型把 `embedding` 设为 true 后允许输出价为 0，并且不走 fallback。
- 健康：有 `SETTLED` 行是 `LIVE`（有真实调用），否则 `CONFIGURED`（已配置）。最近一条已结算行的价格版本和当前版本不同则 `drift=true`。主动探测留给 R4-4。
- 同一 `requestId` 只结算一次预算。失败尝试的费用是 0，不计入已结算。已结算行的费用合计等于这把 Key 的已结算额。
- SDK 调用薄网关时带 `X-Trace-Id`。429 的 SDK 映射本任务不改。

## 验收标准

```bash
python3 scripts/codegen.py
mvn -o -pl :keel-common,:keel-llm,:keel-server -am test -DskipTests
mvn -o -pl :keel-llm -Dtest=GatewayTest,SpendCursorTest,JdbcSpendLedgerTest test
mvn -o -pl :keel-server -Dtest=CostServiceTest test
cd console && npx vitest related src/views/models/modelBill.ts src/views/models/ModelGateway.vue src/views/trace/TraceDetail.vue --run
```

- [x] 游标能翻完所有尝试；翻页之后插入新行，已翻过的页不变。
- [x] 已结算费用合计等于预算里的已结算额；失败尝试不计入。
- [x] 流式客户端在收到 usage 后断开，费用按该 usage；没收到 usage 则待计费。
- [x] `rpm=1` 或 `concurrency=1` 时第二次调用返回 `LLM_RATE_LIMITED`，且没有多占预算。
- [x] 账单页能看到尝试、价格版本和链路跳转；模型行能看到价格版本、健康和漂移；Key 行能看到 RPM / TPM / 并发。

## 明确不做

- 不做路由策略、渠道熔断、试算和告警规则（R4-4）。
- 不把模型页改成五个 tab（R4-3）。
- 不做主动探测，健康里不出现「已探测」。
- 不在仓库里写数据库口令。JDBC 地址只从环境变量来。
