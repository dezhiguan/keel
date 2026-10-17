# models-console-align 模型与成本页对齐原型

## 目标

`#/models` 五个 tab 的列、单价、健康胶囊、按钮和可执行操作与 `docs/console/Keel-控制台前端-20261014.html` 的模型与成本页一致。金额仍以薄网关 `costCny` 为准。

## 依赖

- 前置任务：R4-1、R4-3、R4-4。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `ModelGateway.models` 增加可选单价字段；新增发布、回滚、试算三个接口。
- 依赖的外部组件及其真实行为：薄网关 `POST /admin/v1/route-policy`、`POST /admin/v1/route-policy/rollback`、`POST /admin/v1/route/preview`，见 `.cursor/rules/external-apis.mdc`。熔断状态没有列表接口，页面显示「未接入」。

## 改哪些文件

```
docs/specs/r1014/models-console-align.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/api/models.ts
console/src/views/models/modelBill.ts
console/src/views/models/modelBill.test.ts
console/src/views/models/ModelGateway.vue
console/src/mocks/data/models.ts
keel-server/src/main/java/com/keel/server/integration/litellm/LiteLlmClient.java
keel-server/src/main/java/com/keel/server/insight/CostService.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
```

## 接口契约

- 模型行增加可选 `inputCnyPerToken`、`outputCnyPerToken`、`embedding`。单价单位仍是人民币 / token，页面换算成每百万 token。
- `POST /insight/route-policy` body `{objective}`，objective 为 `balanced`、`cost`、`latency`。
- `POST /insight/route-policy/rollback`。只有一版时 400。
- `POST /insight/route/preview` body `{alias, model}`，返回 `{version, objective, models}`。

## 界面对齐

- 原型位置：`docs/console/Keel-控制台前端-20261014.html` 的 `#/models`，模型与渠道、Key 与配额、路由与降级、账单、告警规则。
- 对齐清单：模型与成本「版式与操作」。
- 页面数据来自 `GET /insight/costs`，写操作走上面三个新接口以及已有的预算、轮换、探测、告警和导出。

## 实现要点

- 单价列是「输入 / 输出 / 缓存」，版本号写在下面。价格版本本身是 `输入/输出/缓存` 拼接串时，版本行显示「未标版本」。
- 健康是最后一列的胶囊。超时率达到波动阈值时胶囊带「波动」。配置漂移用失败色。
- 路由目标芯片会真正发布；回滚和试算走薄网关。试算字段是别名和模型，不用原型里的本地假演算。
- 硬约束文案只写网关实际执行的规则，不写尚未实现的上下文长度和工具能力过滤。
- 渠道熔断状态没有接口，单元格写「未接入」，超时率仍用账单。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=CostServiceTest test
cd console && npx vitest related src/views/models/modelBill.ts src/views/models/ModelGateway.vue --run
```

- [ ] 模型表列顺序与原型一致，单价按每百万 token 显示。
- [ ] Key 表分成 RPM / TPM 与并发，按钮是「调整配额」和「轮换」。
- [ ] 平台管理员可以发布和回滚路由策略，试算结果来自网关。
- [ ] 账单和告警规则的列、导出、新建规则可用。

## 明确不做

- 不在控制台新增模型目录。接入模型只说明配置在薄网关。
- 不猜熔断的打开、半开、关闭。
- 不按原型样例数字写死调用量或费用。
