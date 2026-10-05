# P1-20 控制台环境隔离

## 目标

顶栏环境切换增加 `dev`、`test`。左侧九个功能页展示的数据随当前环境变化：选中单一环境时只出现该环境的数据，选「全部环境」时不做环境过滤。

## 依赖

- 前置任务：P1-4、P1-12、P1-13、P1-14、P1-15、P1-16、P3-1
- 依赖的契约文件：`contracts/console-api.openapi.yaml`、`contracts/audit-event.schema.json`、`contracts/invoke.openapi.yaml`
- 依赖的外部组件及其真实行为：薄网关虚拟 Key 别名后缀区分环境。Langfuse 观测若带 `keel.env` 或 `keel.llm.key_alias`，链路按它过滤；没有环境标记的观测不进入单一环境。rag-forge 知识库没有环境字段，知识库表不按环境拆开。

## 改哪些文件

```
docs/specs/p1/P1-20-console-env-isolation.md
docs/specs/p1/P1-2-model-gateway.md
.cursor/rules/external-apis.mdc
contracts/console-api.openapi.yaml
contracts/audit-event.schema.json
contracts/invoke.openapi.yaml
deploy/litellm/config.yaml
keel-llm/src/main/java/com/keel/llm/Gateway.java
keel-server/src/main/resources/db/migration/V6__env_test.sql
keel-server/src/main/java/com/keel/server/registry/controller/AgentController.java
keel-server/src/main/java/com/keel/server/registry/service/LifecycleService.java
keel-server/src/main/java/com/keel/server/registry/service/AgentRegistryService.java
keel-server/src/main/java/com/keel/server/insight/**
keel-server/src/main/java/com/keel/server/builtin/LocalTraceLog.java
keel-server/src/main/java/com/keel/server/approval/**
keel-server/src/main/java/com/keel/server/tool/controller/ToolController.java
keel-server/src/main/java/com/keel/server/tool/service/ToolRegistryService.java
keel-server/src/main/java/com/keel/server/integration/audit/**
keel-server/src/main/java/com/keel/server/discovery/ReconcileJob.java
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
keel-server/src/test/java/com/keel/server/approval/**
keel-server/src/test/java/com/keel/server/insight/**
keel-server/src/test/java/com/keel/server/builtin/EchoProbeTest.java
keel-server/src/test/java/com/keel/server/tool/service/ToolRegistryServiceTest.java
keel-audit/src/main/java/com/keel/audit/query/AuditQueryController.java
keel-audit/src/main/java/com/keel/audit/query/AuditLog.java
keel-audit/src/test/java/com/keel/audit/masking/AuditMaskQueryTest.java
console/src/stores/env.ts
console/src/stores/approvals.ts
console/src/api/**
console/src/layouts/ConsoleLayout.vue
console/src/views/agents/wizard.ts
console/src/views/agents/AgentCreateWizard.vue
console/src/views/services/SharedServices.vue
console/src/views/eval/EvalView.vue
console/src/views/tools/ToolRegistry.vue
console/src/views/tools/ApprovalInbox.vue
console/src/views/audit/AuditView.vue
```

## 接口契约

环境名：`dev`、`test`、`staging`、`prod`。查询参数 `env` 另加 `all`，默认 `all`。

以下列表接受 `env`：`/insight/overview`、`/agents`、`/insight/services`、`/insight/traces`、`/tools`、`/approvals`、`/runs`、`/audit/events`、`/insight/costs`。

`approval_request.env` 与打开审批单时的环境一致，缺省 `prod`。已有行先从关联 `agent_run.env` 回填，没有关联运行的记为 `prod`。

## 实现要点

- 单一环境只保留能对上环境的行。对不上的（没有环境标记的 Langfuse 观测、没有依赖方且所有者不在该环境的工具）不出现。
- 审批哈希链按智能体串起来，校验接口不按环境切开。
- 虚拟 Key 别名后缀增加 `test`：`^[a-z][a-z0-9-]{1,38}[a-z0-9]-(dev|test|staging|prod)$`。
- 共享服务里的调用方按该环境已注册的智能体过滤；模型成本按 `rag-forge-{env}` 这把 Key。知识库响应没有环境字段，不拆。

## 验收标准

```bash
mvn -o -pl :keel-server test
mvn -o -pl :keel-audit test
mvn -o -pl :keel-llm test
cd console && npx vitest related src/stores/env.ts src/views/eval/EvalView.vue src/views/tools/ToolRegistry.vue src/views/tools/ApprovalInbox.vue src/views/services/SharedServices.vue src/views/agents/wizard.ts
```
