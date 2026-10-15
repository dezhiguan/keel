# R4-7 用户记忆

## 目标

业务智能体可以写入、列出和删除用户记忆。元智能体写入被拒绝。删除之后再查，这条记忆不再出现。

## 依赖

- 前置任务：R1-4。会话记忆仍在 keel-lite，本任务不搬会话原文。
- 依赖的契约文件：`contracts/error-codes.yaml` 的 `MEMORY_WRITE_DENIED`；`contracts/console-api.openapi.yaml` 的记忆接口。
- 依赖的外部组件及其真实行为：无。合规口径按第 24 章已写明的约束执行：评测流量不写；审计只记条数和类别，不记正文。会话原文不进 Keel 库。向量库本任务不建。

## 改哪些文件

```
docs/specs/r1014/R4-7-user-memory.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/error-codes.yaml
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
sdk-python/keel/_generated/errors.py
keel-server/src/main/resources/db/migration/V22__user_memory.sql
keel-server/src/main/java/com/keel/server/memory/MemoryPolicy.java
keel-server/src/main/java/com/keel/server/memory/MemoryService.java
keel-server/src/main/java/com/keel/server/memory/MemoryController.java
keel-server/src/main/java/com/keel/server/integration/audit/JdbcAuditStore.java
keel-server/src/main/java/com/keel/server/registry/model/dto/AgentDetail.java
keel-server/src/test/java/com/keel/server/memory/MemoryPolicyTest.java
keel-server/src/test/java/com/keel/server/memory/MemoryFlowTest.java
keel-server/src/test/java/com/keel/server/registry/model/AgentDetailTest.java
keel-server/src/test/java/com/keel/server/archunit/ArchitectureTest.java
sdk-python/keel/memory_remote.py
sdk-python/keel/context.py
sdk-python/tests/lite/test_user_memory.py
console/src/api/memories.ts
console/src/views/agents/AgentDetail.vue
```

## 接口契约

表 `user_memory`：`agent_name`、`owner_user`、`content`、`created_at`。删除是物理删除。

- `POST /agents/{name}/memories` body `{ownerUser, content, evalRunId?}`。`layer=META` 或评测流量返回 `MEMORY_WRITE_DENIED`。
- `GET /agents/{name}/memories?ownerUser=` 只返回还在的记忆。
- `DELETE /agents/{name}/memories/{id}`。审计 payload 只有 `count` 和 `category`。

`ctx.memory.remember` / `recall` / `forget` 调上面三个接口。`history` / `append` 仍是本地会话。

## 界面对齐

- 原型位置：`#/agents` 详情抽屉的记忆 tab，管理用户记忆。
- 对齐清单：记忆 tab「按层显示；管理用户记忆」。会话层保持 R1-4。任务和经验仍是未接入。
- 页面数据来自 `GET/DELETE /agents/{name}/memories`。不使用原型里的 1206 条。

## 实现要点

- 智能体 `layer=META` 或名字是 `meta-agent` 时拒绝写入。
- 审计白名单增加 `count`、`category`。`content` 不在白名单里，写不进去。
- 召回就是列出未删除的行。删掉之后同一查询为空。

## 验收标准

```bash
python3 scripts/codegen.py && .venv/bin/python scripts/check_contracts.py
mvn -o -pl :keel-common -DskipTests install
mvn -o -pl :keel-server -Dtest=MemoryPolicyTest,MemoryFlowTest,AgentDetailTest,ArchitectureTest test
pytest sdk-python/tests/lite/test_user_memory.py
cd console && npx vitest related src/views/agents/AgentDetail.vue src/views/agents/agentDrawer.ts --run
```

- [x] 元智能体写入返回 `MEMORY_WRITE_DENIED`。
- [x] 删除后的 id 再列出时不存在。
- [x] 审计 payload 没有记忆正文。

## 明确不做

- 不把会话原文写入 Keel 库。
- 不建 pgvector，不做相似度召回。
- 不做任务记忆和经验记忆的写入。
- 不另定保留期限、加密或出境规则。
