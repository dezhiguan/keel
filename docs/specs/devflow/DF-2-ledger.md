# DF-2 研发任务账本

## 目标

用 PostgreSQL 记下研发任务，接管、交还、取消和阶段回报都走同一套状态规则，并写 `config.change` 审计。控制台能读关口页、采纳种子用例；审批和挂起运行能按研发任务筛选。

## 依赖

- 前置任务：DF-1 契约，D0-4 服务身份。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 已有的 devflow、审批 `source`、审计 `kind`。本任务不改契约。
- 外部组件：无。花费先用任务上的 `spent_cny`。薄网关还没有按任务归集 `costCny`。

## 改哪些文件

```
docs/specs/devflow/DF-2-ledger.md
docs/specs/devflow/README.md
keel-server/src/main/resources/db/migration/V10__devflow.sql
keel-server/src/main/java/com/keel/server/devflow/**
keel-server/src/main/java/com/keel/server/approval/**
keel-server/src/main/java/com/keel/server/integration/audit/**
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/main/java/com/keel/server/registry/model/**
keel-server/src/test/java/com/keel/server/devflow/**
keel-server/src/test/java/com/keel/server/approval/InboxSourceTest.java
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
```

设计稿里的 `V8__devflow.sql` 不能用：V8 已是委托同意，V9 已是断言 jti。本任务用 V10。

## 接口契约

沿用 DF-1 和 DF-9a 已写的路径。阶段回报只接受该任务生产者的服务身份。不在关口时，`GET .../review` 和 `POST .../seed-cases` 返回 `409 RUN_NOT_RESUMABLE`。

`GET /approvals` 与 `GET /runs` 的 `source=devflow` 只留 `devflow_job_id` 非空的行。其它取值 `400 SERVER_INVALID_PARAM`。

`GET /audit/events` 增加已有契约里的 `action`、`kind`。`kind` 按 `payload.kind` 等值过滤。

没有任务时，`GET /insight/costs` 不带 `devflowCost`。有任务时用账本 `spent_cny` 汇总。

## 实现要点

- 状态规则放在不访问数据库的类里，单测不依赖 Docker。
- 研发层任务强制 `COLLAB`，生产者是 `meta-agent`。修改 `meta-agent`，或服务身份改自己、越层提交，返回 `DEVFLOW_LINEAGE_FORBIDDEN`。
- 阶段回报把 `costCny` 累加到 `spent_cny`。超出预算不改任务，返回 `DEVFLOW_BUDGET_EXCEEDED`。开发、评审、门禁失败才计修复轮次；用完后任务记为 `FAIL` 并返回 `DEVFLOW_FIX_ROUNDS_EXHAUSTED`。
- 审计 action 仍是 `config.change`。接管和交还的 payload `kind=devflow.takeover`，阶段回报和取消是 `devflow.stage`。`kind` 已在审计白名单里。审计写入失败则不保存这次状态变化。
- `seed-cases` 只记录采纳条数。隐藏考题切分和内容归 DF-8，这里的 `holdoutCount` 为 0，响应里没有用例正文。
- `devflow_holdout` 表先建好，本任务不提供读取。
- 智能体表加可空 `layer`、`devflow_job_id`。没写过的智能体不返回这两个字段。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=DevflowServiceTest,InboxSourceTest test
```

- [ ] 研发任务强制人机协作；修改 meta-agent 被拒绝
- [ ] 需求阶段不能接管；上线观察不能取消
- [ ] 阶段回报推进到下一阶段；超预算和修复轮次用尽使用 DF-1 的错误码
- [ ] 接管审计的 kind 是 `devflow.takeover`
- [ ] 没有任务时不产生 `devflowCost`；`source` 只接受空或 `devflow`

## 明确不做

- 沙箱执行（DF-3）
- Git / CI 工具登记（DF-4）
- 隐藏考题切分、`GET /devflow/holdout`、`EvalResult.holdout`（DF-8）
- 批次文件解析和试产放量（DF-11）
