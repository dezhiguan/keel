# DF-4 登记研发流程工具

## 目标

把设计方案列出的 14 个共享工具写进工具注册表，并把 `git.pr.merge` 绑到「智能体发布审批」。GitHub 适配和分支保护已经在 D0 落地，本任务只补登记。

## 依赖

- 前置任务：D0-4 授权写入接口，D0 Git/CI 服务，DF-3 沙箱。
- 依赖的契约文件：不改。工具名、access、risk 沿用设计方案第 06 节。
- 外部组件：无。不调用 GitHub。

## 改哪些文件

```
docs/specs/devflow/DF-4-tools.md
docs/specs/devflow/README.md
keel-server/src/main/resources/db/migration/V12__devflow_tools.sql
keel-server/src/test/java/com/keel/server/devflow/DevflowToolCatalogTest.java
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
```

## 接口契约

不新增路径。登记结果出现在现有 `GET /api/v1/tools`。

| 工具 | access | risk | 提供方 |
|---|---|---|---|
| `devflow.job.read` | READ | LOW | keel-server |
| `devflow.stage.report` | WRITE | LOW | keel-server |
| `devflow.batch.read` | READ | LOW | keel-server |
| `devflow.catalog.search` | READ | LOW | keel-server |
| `sandbox.run` | EXEC | MID | keel-server |
| `git.repo.create` | WRITE | MID | git-ci |
| `git.branch.push` | WRITE | LOW | git-ci |
| `git.pr.open` | WRITE | LOW | git-ci |
| `git.pr.diff` | READ | LOW | git-ci |
| `git.pr.comment` | WRITE | MID | git-ci |
| `git.pr.merge` | WRITE | HIGH | git-ci |
| `ci.workflow.dispatch` | EXEC | MID | git-ci |
| `ci.log.fetch` | READ | LOW | git-ci |
| `keel.gate.report.read` | READ | LOW | keel-server |

范围都是 `SHARED`，状态 `ONLINE`，版本 `v1`，归属组织「平台」，没有所有者智能体。

`git.pr.merge` 的版本指向审批策略「智能体发布审批」：`subject_type=tool.call`，审批人角色 `ADMIN`，超时 7 天（10080 分钟），冷却 0。其余工具不绑策略。

## 实现要点

- 用 Flyway V12 插入。同名工具或同版本已存在时跳过，不覆盖。
- 不插入 `agent_tool_grant`。meta-agent 和研发员工还没有 `agent` 行，外键插不进去。授权仍由控制台用户调用已有的 `POST /tools/{name}/grants`。
- 沙箱目前按名字放行三个调用方，本任务不改成查授权表。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=DevflowToolCatalogTest test
```

- [ ] 迁移包含上表 14 个工具，access 和 risk 与表一致
- [ ] `git.pr.merge` 绑到「智能体发布审批」，超时 10080 分钟
- [ ] 迁移不写 `agent_tool_grant`

## 明确不做

- 给尚未入库的员工写授权
- 实现 `keel.gate.report.read` 的读取接口（DF-8）
- 改 GitHub 调用或分支保护
- 改沙箱的调用方判断
