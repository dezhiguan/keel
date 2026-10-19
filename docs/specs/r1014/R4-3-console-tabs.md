# R4-3 控制台菜单和分页

## 目标

控制台左侧菜单和页面标题与原型第 3 版一致。总览、平台与能力服务、评测与门禁、模型与成本用 tab 分页，不再把多块内容上下堆在一起。

## 依赖

- 前置任务：R4-1、R1-8。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的共享服务响应增加可选 `impact` 和 `capabilities`。
- 依赖的外部组件及其真实行为：不新增外部调用。状态仍来自现有健康探测。

## 改哪些文件

```
docs/specs/r1014/R4-3-console-tabs.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
keel-server/src/main/java/com/keel/server/insight/SharedServiceMonitor.java
console/src/api/schema.d.ts
console/src/router/nav.ts
console/src/router/nav.test.ts
console/src/router/index.ts
console/src/views/overview/OverviewView.vue
console/src/views/services/SharedServices.vue
console/src/views/services/platform.ts
console/src/views/eval/EvalView.vue
console/src/views/models/ModelGateway.vue
console/src/views/agents/AgentList.vue
console/src/views/jobs/JobBoard.vue
console/src/views/jobs/JobRules.vue
console/src/views/jobs/JobBatches.vue
console/src/views/trace/TraceList.vue
console/src/views/prompts/PromptList.vue
console/src/views/tools/ToolRegistry.vue
console/src/views/tools/ApprovalInbox.vue
console/src/views/audit/AuditView.vue
```

## 接口契约

- 服务行和平台组件增加可选 `impact`。
- `capabilities[]`：`name`、`type`、`provides`、`users`、`manifestField`、`grant`、`status`。没有探测到的状态为空。

## 界面对齐

- 原型位置：左侧菜单；`#/overview` 四个 tab；`#/services` 四个 tab；`#/eval` 三个 tab；`#/models` 五个 tab；`#/jobs`、`#/audit`、`#/approvals` 标题。
- 对齐清单：左侧菜单、运行总览 tab、智能体生产线标题、平台与能力服务三行、评测与门禁 tab、待我处理、审计留痕标题、模型与成本 tab。
- 页面数据来自现有 keel-server 接口。查不到的列显示「未接入」。路由策略和告警规则的内容留给 R4-4，tab 先在。

## 实现要点

- 观测菜单与第 3 版原型一致，不单列质量中心。
- 待我处理继续用同一种卡片，不另起表格。
- 能力服务的「谁在用」当前接口没有这份名单，显示「未接入」。
- 页面先展示后续阶段的技能、渠道发布、结构化提示词、试运行和路由告警入口；尚无接口的内容统一标「占位」，不生成模拟记录、状态或用量。研发沙箱缺少探测结果时显示「未接入」，不能按原型示例填成在线。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=SharedServiceMonitorTest test
cd console && npx vitest related src/router/nav.test.ts src/views/overview/OverviewView.vue src/views/services/SharedServices.vue src/views/eval/EvalView.vue src/views/models/ModelGateway.vue --run
```

- [x] 侧栏名称与原型第 3 版一致。
- [x] 总览、服务、评测、模型页用 tab 切换，不同时堆叠。
- [x] 平台组件有「坏了会怎样」；能力服务状态来自健康探测。

## 明确不做

- 不实现路由策略、熔断和告警规则（R4-4）。
- 技能、提示词结构化、渠道发布只呈现带「占位」标识的页面结构；功能实现仍留给 R4-5、R4-6、R4-8。
- 不编造能力服务的使用方名单。
