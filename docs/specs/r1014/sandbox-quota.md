# 研发沙箱用量

## 目标

平台与能力服务的「研发沙箱」tab 显示 `keel-devflow-sandbox` 的真实配额和排队。读不到时仍显示「未接入」，不把原型里的示例数字填成在线。

## 依赖

- 前置任务：DF-3 沙箱命名空间与 ResourceQuota；DF-9d / R4-3 已有占位行。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `SharedServices.components`。字段已可选，本任务只改说明。
- 依赖的外部组件及其真实行为：集群内读取 `keel-devflow-sandbox` 的 ResourceQuota `sandbox`。`status.used` / `status.hard` 的 `requests.cpu`、`requests.memory`、`pods` 由配额控制器填写。集群外或接口失败不编造。排队和运行中来自 `devflow_sandbox_run` 的 `QUEUED` / `RUNNING`。

## 改哪些文件

```
docs/specs/r1014/sandbox-quota.md
docs/specs/r1014/ui-checklist.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/services/platform.ts
console/src/views/services/platform.test.ts
deploy/k3s/sandbox/namespace.yaml
keel-server/src/main/java/com/keel/server/devflow/SandboxService.java
keel-server/src/main/java/com/keel/server/insight/SharedServiceMonitor.java
keel-server/src/main/java/com/keel/server/integration/k8s/SandboxQuota.java
keel-server/src/test/java/com/keel/server/devflow/SandboxActivityTest.java
keel-server/src/test/java/com/keel/server/insight/SharedServiceMonitorTest.java
keel-server/src/test/java/com/keel/server/integration/k8s/SandboxQuotaTest.java
```

## 接口契约

`GET /api/v1/insight/services` 的 `components[]` 中 `keel-devflow-sandbox`：

- `usage`：`{cpuUsed} / {cpuHard} 核 · {memUsed} / {memHard}Gi · 运行 {running} · 排队 {queued}`。读到配额才填。
- `status`：配额在、且未顶满、排队为 0 时 `ONLINE`；顶满或有排队时 `DEGRADED`；配额对象不存在时 `OFFLINE`。集群外、403 或其他读取失败保持空。

## 界面对齐

- 原型位置：`docs/console/Keel-控制台前端-20261014.html` 的 `#/services`，tab「研发沙箱」，表列：组件、命名空间、状态、用量、说明。用量示例 `1.5 / 4 核 · 3 / 8Gi · 运行 2 · 排队 0` 只是版式。
- 对齐清单：`docs/specs/r1014/ui-checklist.md` 平台与能力服务的「研发沙箱」行。
- 页面数据来自 `GET /api/v1/insight/services` 的 `components`。现有「坏了会怎样」列保留，文案由接口的 `impact` 给出。

## 实现要点

- Role 增加 `resourcequotas` 的 `get`。现有 Role 只能建 Job，读不到配额。
- 核数按 Quantity 的数值（`1500m` = 1.5）。内存按 Gi 显示，单位写在配额一侧，与原型一致。
- 运行中、排队只数账本里的 `RUNNING` / `QUEUED`，不把已结束的 Job 算进去。
- `insight` 只调用 `SandboxService.activity()`，不碰 `SandboxStore`。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=SandboxQuotaTest,SandboxActivityTest,SharedServiceMonitorTest test
cd console && npx vitest related src/views/services/platform.test.ts --run
```

- [ ] 读到配额时 usage 形如 `1.5 / 4 核 · 3 / 8Gi · 运行 2 · 排队 0`，状态为正常或降级
- [ ] 集群外或读取失败时 usage、status 仍为空，页面显示未接入，不出现原型示例数字
- [ ] 配额不存在时状态为离线，用量不编造

## 明确不做

- 不改沙箱 Job 的镜像、网络策略和超时
- 不把原型示例 `1.5 / 4` 写成缺省值
- 不在平台组件 tab 重排现有健康表
