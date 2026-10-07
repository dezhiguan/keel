# P1-13 insight：链路详情组装

## 目标

`GET /api/v1/insight/traces/{traceId}` 返回一份 `TraceDetail`。协作图、泳道、调用树用同一份 `nodes` 和 `edges`。节点上能看到对应的审计事件和审批单。

## 依赖

- 前置任务：P1-1、P1-11。审批单叠加如果 `approval_request` 还没有数据，该字段为空，不阻塞。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `TraceSummary`、`TraceDetail`、`TraceNode`；`contracts/trace-attributes.md`
- 依赖的外部组件：Langfuse `GET /api/public/v2/observations`，按 traceId 取该 trace 的全部 observation。不要调用 `/api/public/traces` 或 `/api/public/observations`，v4 上它们是 404。

## 改哪些文件

```
keel-server/src/main/java/com/keel/server/insight/TraceQueryService.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/main/java/com/keel/server/integration/langfuse/**
keel-server/src/test/java/com/keel/server/insight/**
docs/specs/p1/P1-13-insight-traces.md
```

列表接口 `GET /api/v1/insight/traces` 也在本任务实现，页面在 P1-16 才接。列表同样只走 v2 observations。v2 没有按 trace 分页的接口（`GET /api/public/traces` 对 2026-09-16 之后的组织返回 410）。实现是拉 `/api/public/v2/observations`（按 startTime 从新到旧，最多跟十页游标），在服务端按 `traceId` 分组后再分页。查询要带 `type` 过滤，只留 `AGENT`、`GENERATION`、`TOOL`、`RETRIEVER`、`GUARDRAIL`、`CHAIN`、`EMBEDDING`、`EVALUATOR`。同一个项目里的 FastAPI 探针（`GET /api/health`、`fastapi.*`，类型是 `SPAN`）不滤掉的话，前几页全是探针，昨天的调用进不了这十页。

## 接口契约

```
GET /api/v1/insight/traces
GET /api/v1/insight/traces/{traceId}
```

详情约束（OpenAPI 与 P1-15a 的 mock 已经在用）：

- `edges` 的 `from`、`to` 都存在于 `nodes`
- `latencyBreakdown` 各项 `ms` 之和等于 `summary.durationMs`
- `langfuseUrl` 形如 `{langfuse}/project/{id}/traces/{traceId}`
- `inputSummary` / `outputSummary` 取 Langfuse observation 的 input / output。没有这两项时回退到节点名

## 实现要点

- **一次详情只请求一次 Langfuse。** 三种视图不要各打一次。
- **审计按 `trace_id` 查 keel-audit，审批按 `trace_id` 查本库。** 挂到节点的 `auditIds`、`approvalId` 上。查不到就空数组或 null，不要造一条。
- **成本从 observation 的用量和已配置单价算成人民币。** 汇率与 P1-8 同一个环境变量。没有单价的模型 `costCny` 为 null，不要写成 0 冒充已经计费。
- **人工等待单独标 `humanWaitLabel`。** 审批挂起的时间不算进智能体耗时。没有 `agent_run` 行时不编造这段等待。
- insight 不写业务表。Langfuse 客户端放 `integration/langfuse`，测试用假 HTTP，不要 mock 整个 client。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] 用一份包含两个智能体和一个 retriever 的假 observations，组装结果满足上面三条约束
- [ ] 同一 `trace_id` 的审计事件出现在对应节点的 `auditIds`
- [ ] 假 Langfuse 只收到 v2 observations 请求
- [ ] 响应 JSON 里没有测试原文以外的长文本字段被当成原文返回
- [ ] 未知 traceId 返回 `SERVER_NOT_FOUND`

## 明确不做

- 不改控制台页面（P1-16）
- 不实现总览、成本页、质量、共享服务（P1-14）
- 不把 Langfuse 界面嵌进控制台
