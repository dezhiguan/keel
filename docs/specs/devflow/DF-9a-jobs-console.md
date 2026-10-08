# DF-9a 控制台：研发任务页

## 目标

控制台新增「研发任务」菜单，按 `docs/console/Keel-全流程智能体化控制台.html` 的 `#/jobs`、`#/jobs/:id`、`#/jobs/batches`、`#/jobs/rules` 落地。看板、任务详情、人工接管 / 交还 / 请智能体帮忙 / 取消可以操作；批次和规则页同一套页签里可点。数据来自 MSW，形状按 `contracts/console-api.openapi.yaml`。keel-server 接上后关掉 mock，页面不改。

## 依赖

- 前置任务：无页面级阻塞。devflow 接口是 DF-1 的一部分，本任务把页面用到的路径先写进 console-api；其余 DF-1 字段（`agents.layer`、`devflowJobId`、审批 `source`、`DEVFLOW_*` 错误码、追踪属性）仍归 DF-1。
- 依赖的契约文件：`contracts/console-api.openapi.yaml`。非法状态迁移用已有 `SERVER_INVALID_PARAM`，缺任务用 `SERVER_NOT_FOUND`。
- 外部组件：无。页面只打 keel-server，不打 Langfuse。

## 改哪些文件

```
docs/specs/devflow/DF-9a-jobs-console.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/**
```

## 接口契约

成功体仍是 `{code, message, traceId, data}`。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/devflow/jobs` | 看板。`data.summary` 是顶部数字，`data.items` 是全部任务卡片 |
| GET | `/api/v1/devflow/jobs/{jobId}` | 详情 |
| POST | `/api/v1/devflow/jobs/{jobId}/takeover` | 人工接管。当前用户成为开发者，状态改为 `HUMAN`；阶段在评审则回到开发，已过门禁则停在门禁 |
| POST | `/api/v1/devflow/jobs/{jobId}/handback` | 交还。状态 `RUN`，阶段 `REVIEW`。骨架模式按钮文案是「提交门禁」 |
| POST | `/api/v1/devflow/jobs/{jobId}/assist` | body `{instruction}`。只在人工开发中可用，记两条动态 |
| POST | `/api/v1/devflow/jobs/{jobId}/cancel` | 取消。上线观察中拒绝 |
| GET | `/api/v1/devflow/batches` | 批次列表，每条带所属任务 |
| POST | `/api/v1/devflow/batches/preview` | 草案。mock 返回原型里的 5 行预检，真解析归 DF-11 |
| POST | `/api/v1/devflow/batches` | 创建批次，跳过带 flag 的行；第一条为试产 `RUN`，其余 `QUEUED` |
| GET / PUT | `/api/v1/devflow/settings` | 规则。模板列表不能清空 |

可接管：`RUN` 且阶段为开发 / 评审 / 门禁，或 `FAIL`，或等人 approve PR（`WAIT` 且 `needReview`）。可取消：`RUN` / `WAIT` / `HUMAN` / `QUEUED`，且阶段不是上线观察。

看板 30 秒轮询，进行中的详情 10 秒轮询；页面不可见时不请求。

## 实现要点

- 菜单放在「运营」、智能体之后。页签：看板、批次、规则。
- 阶段条、状态文案、可接管判断抽到 `views/jobs/jobs.ts`，mock 的状态迁移调用同一组函数。
- 产物有没有，按当前阶段推导，和原型一致。manifest 在详情里展开；其余「查看」提示尚未接入。关口处理页归 DF-9c，详情上的「去审批中心处理」只进审批中心列表。
- 首次门禁通过率 54%、三轮内 83% 是原型样本，写在 summary 里。真实统计归 DF-9d。
- 预览模式写按钮走 `v-write`。规则页非平台管理员只读。
- 原型左下角的身份切换和「前端方案」菜单不做。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/jobs/jobs.test.ts src/mocks/handlers.test.ts src/router/nav.test.ts
```

- [ ] `/jobs` 看板五列、进行中卡片、最近结束表与原型同一批样例任务
- [ ] 点卡片进详情：进度时间线、产物、评测 / 链路 / 成本三个标签
- [ ] 可接管的任务确认后变为人工开发；已完成的任务接管返回 400 `SERVER_INVALID_PARAM`
- [ ] 人工开发中可以交还、可以请智能体帮忙；空指令被拒
- [ ] 上线观察中的任务不能取消
- [ ] 批次页能看到 B-03，上传预检后可创建下一批；规则页能保存预算和模板

## 明确不做

- keel-server 的 devflow 模块（DF-2）和批次账本的真实校验（DF-11）
- 审批中心里的需求确认 / 评测确认 / 发布审批页（DF-9c）
- 智能体页的分类、来源、谱系（DF-9b）
- 任务自动往前推进的时钟模拟
