# P1-3 keel-server 数据库迁移

## 目标

在已有 V1 registry 迁移之后，用 Flyway 建齐技术文档第 06 节 `keel` 库的业务表，并在 PostgreSQL 上验证迁移、约束和关键索引。

## 依赖

- 前置任务：P0-4、P1-0。
- 契约：`docs/specs/p0/P0-1a-run-lifecycle.md` 定义 `agent_run` 和审批主体；数据库表以 `docs/architecture/Keel-技术文档.html` 第 06 节为准。
- 外部组件：PostgreSQL 16；CI 用 Testcontainers，本地无 Docker 时测试按仓库约定跳过。

## 改哪些文件

```
docs/specs/p1/P1-3-keel-server-schema.md
keel-server/src/main/resources/db/migration/V2__platform.sql
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
```

## 表结构

V1 已有 `agent`、`agent_version`、`agent_instance`，不可修改。V2 增加 `agent_resource`、`reconcile_finding`、`tool`、`tool_version`、`agent_tool_grant`、`approval_policy`、`approval_request`、`agent_run`、`release_record`、`route_snapshot`。技术文档共列 13 张 `keel` 库表，故 V1 之后实际还需 **10 张**；`docs/TASKS.md` 的“12 张／九张”计数有误。

- `agent_run.run_id` 为主键，`(agent_name, idempotency_key)` 唯一；`idempotency_key` 可空。
- `approval_request.run_id` 可空并引用 `agent_run`；`subject_type` 允许 P0-1a 的五种审批主体。
- `tool_version` 的 `(tool_name, version)` 唯一，`approval_policy_id` 可空。
- 各表 `env` 限定 `dev/staging/prod`，风险、状态等文档给定枚举由数据库 `CHECK` 约束。
- `route_snapshot.version` 自增，供网关按版本长轮询。

## 实现要点

- 所有建表语句只在 V2，保持已执行的 V1 校验和稳定。
- 用外键保护明确的注册表关系；对可能先于注册而出现的对账发现保留 `agent_name` 文本，不强制关联 `agent`。
- 对审批运行状态、待办列表、实例对账、工具授权和路由轮询建立实用索引。
- `keel_audit` 是独立数据库，不在本迁移中创建审计表。

## 验收标准

```bash
mvn -q -pl :keel-server -am test
```

- [ ] PostgreSQL 16 Testcontainers 执行 Flyway V1、V2 成功。
- [ ] 数据库包含 13 张业务表，`agent_run`、`approval_request` 的约束和外键可用。
- [ ] 现有服务测试与 ArchUnit 测试通过。

## 明确不做

- 不实现 CRUD、审批状态机、路由同步或审计库迁移。
- 不修改契约、V1 或 local seed。
