# P1-16 控制台链路追踪：列表与三视图

## 目标

链路追踪页先是可筛选、可分页的 trace 列表。点一行进入详情，协作图、泳道、调用树三块用同一次详情响应。

## 依赖

- 前置任务：P1-13、P1-15
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `/insight/traces`、`/insight/traces/{traceId}`
- 依赖的外部组件：无。跳转 Langfuse 用详情里的 `langfuseUrl`，前端不自己拼公钥。

## 改哪些文件

```
console/src/api/traces.ts
console/src/views/trace/**
console/src/mocks/handlers.ts
console/src/**/*trace*.test.ts
docs/specs/p1/P1-16-console-traces.md
```

P1-15a 已经有列表和三视图。本任务去掉这两条接口的 mock，并核对筛选是否真的发给服务端。

## 接口契约

```
GET /api/v1/insight/traces?env&agent&status&minDurationMs&from&to&page&size
GET /api/v1/insight/traces/{traceId}
```

详情只请求一次。三视图的数据都来自 `nodes` 和 `edges`。

## 实现要点

- **列表不是写死的五条最近调用。** 原型里那五条只是示意。筛选智能体、状态和分页都要进查询参数。
- **协作图继续按 `edges` 分层，SVG 自绘。** 不要为了接真数据引入图形库。
- **`latencyBreakdown` 之和与 `durationMs` 不一致时显示服务端数据，不要在前端重新分配耗时。** 这种不一致是后端缺陷，测试应在 P1-13 抓住。
- mock handler 里删除或停用 `/insight/traces`。其他 mock 不动。
- 空列表、404 的 trace、节点上的 `approvalId` 链接到审批页（审批页仍可以是 mock）都要能打开，不能白屏。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/trace src/api/traces.ts
```

- [x] 改变智能体筛选后，请求的 `agent` 参数跟着变，页码回到第一页
- [x] 进入详情后，网络面板里对该 `traceId` 只有一次 GET
- [x] 三视图的节点数量一致
- [x] `langfuseUrl` 作为外链渲染，页面不请求 Langfuse 域名

## 明确不做

- 不改 `TraceQueryService`（P1-13）
- 不实现评测中心（P2-12）
- 不在图里展示用户原文
