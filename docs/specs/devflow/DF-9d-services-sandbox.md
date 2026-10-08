# DF-9d 共享服务：平台组件表加研发沙箱

## 目标

共享服务页保留服务健康、rag-forge 分段耗时和知识库。平台组件表列出 keel-server、keel-llm、keel-audit、keel-gateway、console，并多一行 `keel-devflow-sandbox`。配额还没接上，沙箱用量用占位。

## 依赖

- 前置任务：无。沙箱命名空间和配额归 DF-3，本任务不探活那个命名空间。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `SharedServices.components`，字段全部可选。
- 外部组件：无。

## 改哪些文件

```
docs/specs/devflow/DF-9d-services-sandbox.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/services/**
console/src/mocks/data/services.ts
console/src/mocks/handlers.test.ts
keel-server/src/main/java/com/keel/server/insight/SharedServiceMonitor.java
keel-server/src/test/java/com/keel/server/insight/SharedServiceMonitorTest.java
```

## 接口契约

`GET /api/v1/insight/services` 的 `data.components[]`：`name`、`namespace`、`status`、`usage`、`note`。查不到的 `status` / `usage` 为空，不编造健康。

## 实现要点

- 服务健康表的行和列不变。平台组件是下面另一张表。
- 已有组件的状态从同一份探测结果抄过来：`keel-llm` 对应服务健康里的「薄网关」。控制台和沙箱这次不新加探测。
- 沙箱的 `status` 和 `usage` 都为空时，页面显示原型里的占位数字，并注明是占位。接口一旦带回用量或状态，页面不再替换。

## 验收标准

```bash
mvn -o -pl :keel-server test -Dtest=SharedServiceMonitorTest
cd console && npx vitest related src/views/services/platform.test.ts src/mocks/handlers.test.ts
```

- [ ] 服务健康、分段耗时、知识库仍在
- [ ] 平台组件表有原来的五个组件，加 `keel-devflow-sandbox`
- [ ] 沙箱没有配额数据时，用量是占位，说明写一次性 Job、10 分钟超时、只出包镜像源

## 明确不做

- 总览、链路、评测、工具、审计、模型网关的增强
- 读取 `keel-devflow-sandbox` 的 ResourceQuota（DF-3）
