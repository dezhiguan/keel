# R3-4 删除已下线智能体

## 目标

`DELETE /api/v1/agents/{name}` 只接受已下线的智能体。删除后它不再出现在列表和详情里，同名不能再注册。审计记录和 Langfuse 链路不删除。

## 依赖

- 前置任务：无。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 已有 `deleteAgent` 和 `AGENT_NOT_RETIRED`。本任务不改契约。
- 依赖的外部组件及其真实行为：不调用 Langfuse 删除数据集或 trace。

## 改哪些文件

```
docs/specs/r1014/R3-4-delete-agent.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
keel-server/src/main/resources/db/migration/V19__agent_removed.sql
keel-server/src/main/java/com/keel/server/registry/model/entity/Agent.java
keel-server/src/main/java/com/keel/server/registry/service/LifecycleService.java
keel-server/src/main/java/com/keel/server/registry/service/AgentRegistryService.java
keel-server/src/main/java/com/keel/server/registry/controller/AgentController.java
keel-server/src/test/java/com/keel/server/registry/service/AgentDeletionTest.java
```

## 接口契约

- 未下线：409 `AGENT_NOT_RETIRED`。
- 已下线：登记行保留，`removed=true`，列表和详情不再返回。
- 之后同名注册返回 `AGENT_NAME_TAKEN`。
- 不执行 `DELETE` 审计表，不调用 Langfuse 删除接口。

## 界面对齐

- 原型位置：`#/agents` 详情抽屉底部。删除按钮只在已下线时出现，页面里已经如此。
- 对齐清单：智能体目录「删除按钮」R3-4。
- 页面数据：已有的 `DELETE /api/v1/agents/{name}`。

## 实现要点

- 用 `removed` 标记而不是改 `status` 枚举，避免控制台状态多出一个对外值。
- 删除不回收名称，因为名称检查数的是登记行是否还在。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=AgentDeletionTest test
```

- [x] 在线智能体删除抛出 `AGENT_NOT_RETIRED`，不写 `removed`。
- [x] 已下线智能体删除只把 `removed` 设为 true，不调用审计删除。
- [x] 已删除的名字再次注册返回 `AGENT_NAME_TAKEN`。

## 明确不做

- 不删除 `audit_event`，不删除 Langfuse 的 trace 或数据集。
- 不改下线流程本身。
