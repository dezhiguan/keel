# DF-9d 控制台：链路追踪按研发任务筛选

## 目标

链路追踪列表在现有筛选和列上增加一项：按研发任务筛选，并在每一行标出来源任务。详情仍是泳道、调用树，多智能体时还有协作图，这三块不改。

来源是 `docs/console/Keel-全流程智能体化控制台.html` 的链路追踪增强，以及 `docs/design/Keel-全流程智能体化方案.html` 里「按 `keel.devflow.job_id` 过滤，行上标来源」。

## 依赖

- 前置：P1-16 的列表和三视图。研发任务接口（DF-2）还没有时，筛选项用占位任务，不挡页面。
- 契约：`contracts/console-api.openapi.yaml` 的 `GET /insight/traces`；`contracts/trace-attributes.md` 登记根节点属性。
- 本切片只登记追踪属性 `keel.devflow.job_id` 和可选的 `keel.devflow.label`。DF-1 其余字段仍归 DF-1。

## 改哪些文件

```
docs/specs/devflow/DF-9d-traces.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
contracts/trace-attributes.md
console/src/api/schema.d.ts
console/src/views/trace/TraceList.vue
console/src/views/trace/traceView.ts
console/src/views/trace/traceView.test.ts
keel-server/src/main/java/com/keel/server/insight/TraceAssembly.java
keel-server/src/main/java/com/keel/server/insight/TraceQueryService.java
keel-server/src/main/java/com/keel/server/insight/controller/InsightController.java
keel-server/src/test/java/com/keel/server/insight/TraceQueryServiceTest.java
```

## 接口契约

`GET /api/v1/insight/traces` 增加可选查询参数 `jobId`。`TraceSummary` 增加可选字段：

| 字段 | 来源 | 没有时 |
|---|---|---|
| `devflowJobId` | 根节点 `keel.devflow.job_id` | 省略 |
| `devflowLabel` | 根节点 `keel.devflow.label`，例如「第 2 轮门禁」 | 省略，行上只显示任务号 |

两个字段都可选，不升契约版本。

## 实现要点

- 现有的智能体、状态、时间窗、多智能体筛选和分页保持不变。详情三视图不改。
- 选中研发任务后，请求带 `jobId`。服务端按根节点属性过滤；旧响应没有该字段时，前端再按 `devflowJobId` 收一次。
- 行上的来源链到 `/jobs/{jobId}`，文案是 `DF-0019 · 第 2 轮门禁`。没有标签时只显示任务号。点来源不进入 trace 详情。
- 研发任务列表接口失败或为空时，筛选项用 `DF-0019`。选中任务后一条匹配链路都没有时，表格放入原型里的两行占位，并标「占位」，不把占位行当成可打开的 trace。
- 没有任务号的真实链路，来源列显示「—」。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/trace/traceView.test.ts
mvn -o -pl :keel-server test -Dtest=TraceQueryServiceTest
```

- [ ] 不选研发任务时，列表列和筛选与原来一致，只多一列来源
- [ ] 选中任务后请求带 `jobId`，页码回到第一页
- [ ] 根节点带 `keel.devflow.job_id` 的 trace 能被筛出来，并带上标签
- [ ] 没有匹配链路时看到占位行，详情页仍是原来的三视图

## 明确不做

- 总览、共享服务、评测、工具、审计、模型网关的增强（DF-9d 的其余页面）
- keel-server 的 devflow 任务账本（DF-2）
- 改详情三视图的数据或布局
