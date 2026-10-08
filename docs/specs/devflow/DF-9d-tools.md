# DF-9d 控制台：工具页显示研发流程工具

## 目标

工具注册表现有的列表、详情、废弃、下线、授权不变，多一个"研发流程"筛选项，把研发流程用到的平台工具（devflow、sandbox、git、ci、门禁报告）归成一组显示，并在行上标出授予给哪些研发智能体。

来源：`docs/console/Keel-全流程智能体化控制台.html` 工具增强；`docs/design/Keel-全流程智能体化方案.html` 第 06 节"新增共享工具"。

## 依赖

- 前置：现有工具页（`GET /tools`）。
- 契约：不改。
- 外部：这些工具要等 D0-4（工具注册表的服务身份与授权写入）和 DF-4（Git / CI MCP 服务）完成后才会真正登记。登记前部署环境里这一组为空，本地由 MSW 返回样例。

## 改哪些文件

```
docs/specs/devflow/DF-9d-tools.md
docs/specs/devflow/README.md
console/src/views/tools/ToolRegistry.vue
console/src/views/tools/toolDrawer.ts
console/src/views/tools/toolDrawer.test.ts
console/src/mocks/data/tools.ts
```

## 接口契约

不改契约。"是否研发流程工具"由工具名前缀判断，写成纯函数：

| 前缀 | 用途 | 授予给（展示用，来自设计方案） |
|---|---|---|
| `devflow.` | 研发任务读写、目录查询 | meta-agent、全部研发智能体 |
| `sandbox.` | 沙箱执行 | meta-agent、dev-agent、eval-agent |
| `git.` | 建仓库、推分支、开 PR、评审、合并 | meta-agent、dev-agent、code-review、release-agent |
| `ci.` | 触发 workflow、读日志 | meta-agent、release-agent、ci-doctor |
| `keel.gate.` | 读门禁报告 | release-agent、ci-doctor |

"授予给"一列以真实授权数据为准；工具详情里已有依赖方数据时用依赖方，没有时用上表兜底并标"按设计"。

## 实现要点

- 筛选项放在现有 scope / status / risk 筛选旁边，选中后只显示前缀命中的工具；不选时列表与原来完全一致。
- 研发流程工具的行上加一个"研发流程"标签。`git.pr.merge` 为 high 风险，沿用现有风险配色；提示"绑定智能体发布审批策略"。
- mock 里补齐设计方案列出的工具：`devflow.job.read`、`devflow.stage.report`、`devflow.batch.read`、`devflow.catalog.search`、`sandbox.run`、`git.repo.create`、`git.branch.push`、`git.pr.open`、`git.pr.diff`、`git.pr.comment`、`git.pr.merge`、`ci.workflow.dispatch`、`ci.log.fetch`、`keel.gate.report.read`，风险等级与设计方案一致。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/tools/toolDrawer.test.ts
```

- [ ] 不选"研发流程"时，列表和原来一致
- [ ] 选中后只显示上表前缀的工具，`git.pr.merge` 显示 high
- [ ] 前缀判断的纯函数有测试（含 `gitlab.x` 这类不该命中的名字）

## 明确不做

- 真正登记这些工具、授权写入（D0-4、DF-4）
- 给 `ToolSummary` 加字段
