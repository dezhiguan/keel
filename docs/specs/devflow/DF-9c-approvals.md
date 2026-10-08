# DF-9c 控制台：审批中心「研发任务」分组与关口处理页

## 目标

审批中心在现有五个分组之外加「研发任务」分组，研发任务的需求确认（H1）、评测确认（H2）、发布审批（H4）和协作 review 都在这里出现；点「处理」进入对应的关口处理页，处理完研发任务继续。现有五类审批单和人工介入的展示、批准、驳回、回复不改。

来源：`docs/console/Keel-全流程智能体化控制台.html` 的审批中心与关口处理页；`docs/design/Keel-全流程智能体化方案.html` 第 03、06 节。

## 依赖

- 前置：P3-1 审批中心（现有接口）、DF-9a 研发任务页（`/devflow/jobs`）。
- 契约：`contracts/console-api.openapi.yaml` 的 `Approval`、`SuspendedRun`、`/devflow/jobs/{jobId}`。
- 外部：研发任务账本（DF-2）还没有，keel-server 的 devflow 是空内存账本。真实审批单和挂起运行暂时不会带研发任务字段，部署环境里这个分组为空属正常；本地由 MSW 按契约返回。

## 改哪些文件

```
docs/specs/devflow/DF-9c-approvals.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/api/devflow.ts
console/src/views/tools/ApprovalInbox.vue
console/src/views/tools/review/**
console/src/views/tools/approvalGroups.ts
console/src/views/tools/approvalGroups.test.ts
console/src/router/index.ts
console/src/views/jobs/JobDetail.vue
console/src/mocks/data/approvals.ts
console/src/mocks/data/jobs.ts
console/src/mocks/handlers.ts
console/src/mocks/handlers.test.ts
```

本切片只做前端：契约、页面、MSW。keel-server 的两个新接口和审批单 / 挂起运行上的研发任务字段归 DF-2，见 `README.md` 的 DF-2 一行。

## 接口契约

都是可选字段，不升契约版本。

`Approval`、`SuspendedRun` 各加：

| 字段 | 类型 | 说明 |
|---|---|---|
| `devflowJobId` | string，可空 | 这张单 / 这次挂起属于哪个研发任务。没有则省略 |
| `devflowGate` | enum `H1` `H2` `H4`，可空 | 对应关口。H1、H2 只出现在 `SuspendedRun`（`reason=input_required`），H4 只出现在 `Approval`（`subjectType=tool.call`、`subjectRef=git.pr.merge`） |

新增 `GET /devflow/jobs/{jobId}/review` → `DevflowReview`：

```yaml
DevflowReview:
  required: [jobId, gate]
  jobId: string
  gate: enum [H1, H2, H4, PR]        # PR = 人机协作等人工 approve
  runId: string, nullable             # H1 / H2 恢复用
  approvalId: string, nullable        # H4 决策用
  specSheet:                          # H1
    { title, goal, users, io, success, pending: string[] }
  suggestedMode: DevflowMode, nullable
  manifestYaml: string, nullable      # H1
  authList: [{ resource, risk: Risk, owner }]   # H1，H3 会发给所有者
  humanCount: integer, nullable       # H2，人给用例条数
  holdoutRatio: number, nullable      # H2，0～1
  cases: [{ caseId, tag, input, expected, reason }]   # H2，eval-agent 扩充的用例
  gateReport:                         # H4
    { visible: number, holdout: number, minScore: number,
      byTag: [{ tag, visible, holdout }] }
  ownership:                          # H4
    { agent, version, tools: string[], dailyBudgetCny, agentCommits: integer, humanCommits: integer }
  pr:                                 # PR
    { number: integer, url: string, additions, deletions, files, reviewSummary, sandboxSummary }
```

新增 `POST /devflow/jobs/{jobId}/seed-cases`，body `{ acceptedCaseIds: string[] }` → `{ humanCount, holdoutCount, acceptedAgentCount }`。隐藏考题内容任何时候都不返回，只返回条数。

关口决策沿用现有接口，不新增：

| 关口 | 确认 | 退回 / 驳回 |
|---|---|---|
| H1 | `POST /runs/{runId}/input` text=「确认」 | 同一接口，text=修改意见（必填） |
| H2 | 先 `POST /devflow/jobs/{id}/seed-cases`，再 `POST /runs/{runId}/input` text=「确认」 | — |
| H4 | `POST /approvals/{approvalId}/decision` APPROVE | 同一接口 REJECT |
| PR | 不在控制台 approve，只给「打开 PR」和「我来改」（接管）入口 | — |

## 实现要点

- 分组判断写成纯函数（`approvalGroups.ts`）：带 `devflowJobId` 的审批单和挂起运行进「研发任务」，同时**不再**出现在原来的「工具审批」「人工介入」分组，避免同一件事出现两次；「全部」里只算一次。协作 review 来自 `GET /devflow/jobs` 里 `status=WAIT && needReview` 的任务。
- 「研发任务」分组放在「全部」之后。每条显示：关口标签（需求确认 / 评测确认 / 发布审批 / 协作 review）、研发任务号（链到 `/jobs/{id}`）、目标智能体、处理按钮。处理按钮进 `/approvals/devflow/{jobId}`。
- 左侧审批中心的待办数加上协作 review 条数，研发任务的 H1 / H2 / H4 已经在原有计数里，不重复加。
- 关口处理页 `/approvals/devflow/:jobId`，按 `review.gate` 渲染四个子组件之一，布局照原型：
  - H1：可编辑的需求单、开发模式选择（研发层锁定人机协作）、授权清单、manifest 预览、修改意见；退回时修改意见必填。
  - H2：人给条数与将切出的隐藏考题条数；扩充用例勾选表（默认全选，价值低的由数据里的 `reason` 自行判断，不在前端写规则）；确认按钮显示采纳条数。
  - H4：可见用例与隐藏考题两个分数环（≥ minScore 绿色，否则红色）、分维度表、上线后拥有的权限与预算、代码来源（智能体 / 人工提交数）。
  - PR：改动规模、code-review 结论、沙箱结果；「打开 PR」跳 `pr.url`，「我来改」调现有接管接口。
- 处理成功后回到研发任务详情页。所有写按钮用 `v-write`，预览模式下禁用。
- 研发任务详情页现有的「去审批中心处理」按钮改为直接进 `/approvals/devflow/{jobId}`。
- 处理页的接口返回 404（部署环境里 keel-server 还没有这两个接口）时，显示"研发任务账本接入后可处理（DF-2）"，不报错弹窗。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/tools/approvalGroups.test.ts src/mocks/handlers.test.ts
```

- [ ] 现有五个分组的条数、卡片、批准 / 驳回 / 回复行为不变（不带研发任务字段的数据）
- [ ] 带 `devflowJobId` 的数据只出现在「研发任务」分组，不在原分组重复
- [ ] 四种关口都能从审批中心进入处理页，处理后回到任务详情
- [ ] H1 退回不填意见时不能提交；H2 确认会先调 seed-cases 再回复「确认」
- [ ] 隐藏考题只显示条数，页面和 mock 响应里都没有内容
- [ ] review 接口 404 时页面显示占位说明，不弹错误

## 明确不做

- 在控制台里替人 approve PR（走 Git 托管 + webhook，归 DF-4）
- H3 授权审批的新页面（沿用工具页和现有审批单）
- keel-server 的 review / seed-cases 接口、审批单与挂起运行上的研发任务字段、研发任务账本（DF-2）
- 改现有五类审批单和人工介入的交互
