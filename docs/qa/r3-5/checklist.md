# R3-5 操作清单

在自己的终端执行。脚本不带默认主机，也不代批发布。

## 执行前

- [ ] 元智能体已经在目标环境可用。创建接口要求平台管理员，且服务端已配置元智能体地址。
- [ ] 导出 `KEEL_SERVER_URL`（源站，不带 `/api/v1`）。
- [ ] 从浏览器复制控制台访问 Cookie，设为 `KEEL_CONSOLE_COOKIE`。生产环境是 `__Host-keel_console_at=<值>`，local profile 是 `keel_console_at=<值>`。不要把 Cookie 写进仓库。
- [ ] 需要时只改 `job.json` 的 `dailyBudgetCny`（大于等于 1）。不要改岗位、模板、工具或 `execution`。

## 创建

```bash
chmod +x docs/qa/r3-5/run-first-employee.sh
docs/qa/r3-5/run-first-employee.sh --dry-run
docs/qa/r3-5/run-first-employee.sh create
```

- [ ] `evidence/create.json` 里有 `jobId`。记下它，后面设为 `KEEL_JOB_ID`。
- [ ] 名称已被占用时换任务前先停。不要改成另一个岗位来绕过原型清单。

## 人工关口

脚本到此为止。下面在控制台完成，不要用脚本批准。

- [ ] H1：核对需求草稿后确认。
- [ ] H2：上传人工种子用例，并确认评测用例。
- [ ] H3：按注册表里的真实版本批准 `devflow.catalog.search` 和 `devflow.stage.report`。
- [ ] 在 GitHub 上评审并批准当前提交的 PR，再回到控制台刷新人工评审、跑代码评审。
- [ ] staging 门禁和隐藏考题由 CI 跑过。
- [ ] H4：在审批中心批准。脚本不调用 `/h4/request`，也不提交审批决定。
- [ ] prod 由 CI 的 `keel release` 发布。发布后服务端进入 `WATCHING`。

## 七天观察

- [ ] 观察由服务端按日写入。未满 7 个健康日时，阶段不是 `DONE`。
- [ ] 满 7 天后执行：

```bash
KEEL_JOB_ID=<上面的任务号> docs/qa/r3-5/run-first-employee.sh collect
```

- [ ] `evidence/verdict.txt` 的 `specAgentInCatalog=yes`，`phase=DONE`，`watchDays` 至少 7。
- [ ] `doneCount` 是看板里 `status=DONE` 的条数，总览「本月上线」用的就是这个数，要大于等于 1。
- [ ] 智能体目录里 `spec-agent` 的来源能对上 `meta-agent · <jobId>`。`layer` 为 `DEV` 且带这个 `devflowJobId` 时，页面才显示该来源。

## 写回报告

把任务号、阶段、目录来源和 `doneCount` 填进 `docs/qa/2026-10-15-r3-5-first-employee.md`。没有证据的行保持「未执行」。
