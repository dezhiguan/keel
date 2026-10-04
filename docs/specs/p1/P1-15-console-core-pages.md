# P1-15 控制台接上总览、智能体、审计、模型网关

## 目标

总览、智能体、审计中心、模型网关四个页面的数据来自 keel-server。这四个页面不再走 MSW。其余页面的 mock 保持原样。

## 依赖

- 前置任务：P1-14、P1-12、P1-4、P1-15a
- 依赖的契约文件：`contracts/console-api.openapi.yaml`
- 依赖的外部组件：无。浏览器只访问 keel-server，不直接访问 Langfuse 或薄网关。

## 改哪些文件

```
console/src/api/{agents,audit,models,http}.ts
console/src/mocks/**
console/src/views/{overview,agents,audit,models}/**
console/src/**/*.test.ts
docs/specs/p1/P1-15-console-core-pages.md
```

页面在 P1-0、P1-15a 已经按原型搭好。本任务改数据来源，不重做布局。

## 接口契约

```
GET /api/v1/insight/overview
GET /api/v1/agents
GET /api/v1/agents/{name}
GET /api/v1/audit/events
POST /api/v1/audit/verify
GET /api/v1/insight/costs
```

`POST /api/v1/audit/exports` 可以接上，但批准前界面只显示「已提交审批」。不要在前端把导出文件造出来。

## 实现要点

- **只关掉这四页用到的 mock handler。** `/insight/traces`、`/eval`、`/tools`、`/approvals`、`/runs`、`/insight/services` 继续由 MSW 提供。`VITE_API_MOCK=off` 时这四页必须仍能工作，其余页允许空数据。
- **智能体详情抽屉改接 `GET /agents/{name}`。** 新建向导仍然是占位：`POST /agents` 的开通要等 P1-6 在环境里可用，本任务不模拟注册成功。
- **列表上仍为 null 的成本、评分显示为「—」。** 不要把 null 渲染成 0。
- **哈希链校验展示 `intact` 和 `brokenAt`。** 不要在前端重算 hash。
- 类型继续用 `openapi-typescript` 生成的 `schema.d.ts`。契约没改就不要手改 `schema.d.ts`。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/overview src/views/agents src/views/audit src/views/models src/mocks
```

本地页面：keel-server 用 local profile 已启动时，`npm run dev` 打开总览、智能体、审计、模型网关，网络面板里这四页的请求打到 `:8080`，不打到 MSW。

- [x] `VITE_API_MOCK` 默认开启时，追踪、评测、工具、审批、共享服务仍返回 mock
- [x] 总览的调用量、成本与 `GET /insight/overview` 的 JSON 一致
- [x] 审计筛选条件会进查询参数
- [x] 模型页 `priceConfigured=false` 时能看出该模型未计费，而不是只显示 0

## 明确不做

- 不接链路追踪页（P1-16）
- 不接评测、质量、共享服务（P2-12）
- 不接工具和审批（P3-3）
- 不实现新建智能体向导的真注册
