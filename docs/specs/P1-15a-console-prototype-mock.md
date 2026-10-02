# P1-15a 控制台七个页面按原型落地（mock 数据）

## 目标

`npm run dev` 后，共享服务、链路追踪（列表 + 详情三视图）、评测中心、工具（列表 + 详情）、审批中心、审计中心、模型网关七个页面按 `docs/console/Keel-控制台前端.html` 的布局可用，数据来自 MSW mock，形状严格按 `contracts/console-api.openapi.yaml`。keel-server 实现对应接口后，关掉 mock 即切到真接口，页面代码不改。

## 依赖

- 前置任务：P1-0
- 依赖的契约文件：`contracts/console-api.openapi.yaml`、`contracts/error-codes.yaml`
- 外部组件：无

## 改哪些文件

```
contracts/console-api.openapi.yaml   # Approval 加 subjectType/subjectRef/runId/policyName；新增 SuspendedRun、GET /runs、POST /runs/{runId}/input（均为草案，P3-1 定稿）
contracts/error-codes.yaml           # 新增 TOOL_HAS_PROD_DEPENDENTS
console/**
```

## 实现要点

- mock 只拦本任务的接口；`/me`、`/insight/overview`、`/agents` 不拦，照常走 keel-server。`VITE_API_MOCK=off` 关闭全部 mock。
- 页面只调 `src/api/*`，不直接 import `src/mocks`。
- 链路详情只请求一次 `/insight/traces/{id}`，协作图 / 泳道 / 调用树共用这份 `nodes` + `edges`。协作图按 `edges` 分层自动布局，SVG 自绘，不引图形库。
- 原型里写死的五条「最近调用」改成真列表：可按智能体、状态筛选，可分页。
- 审批中心按 `subjectType` 分组；「人工介入」来自 `/runs`（挂起的执行，没有审批单）。
- 状态色统一走 `StatusPill`。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest run
npm run dev   # 逐页打开七个页面
```

- [ ] mock 数据满足契约里的约束：`latencyBreakdown` 之和等于 `durationMs`，`edges` 两端都在 `nodes` 里
- [ ] 有 prod 依赖的工具下线返回 409 `TOOL_HAS_PROD_DEPENDENTS`
- [ ] 审批单只能处理一次，第二次返回 409

## 明确不做

- keel-server 侧的这些接口实现（P1-13 / P1-14 / P2-10 / P3-1 / P3-3）
- 注册 MCP 工具、发布工具新版本、调整预算、标记预期变化、终止挂起执行：按钮在，点了提示「尚未接入」
- 智能体详情、新建向导（仍是占位）
