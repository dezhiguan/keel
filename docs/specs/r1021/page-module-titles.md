# R1021 页面与导航名称对齐

## 目标

按用户截图将页面主标题和顶部面包屑与最新左侧名称统一：智能体管理、智能体研发、基础服务、链路追踪、测评中心、模型网关、审批中心、审计中心。测评中心按本次最新措辞同步侧栏。

去掉智能体管理标题旁的注册中心说明；智能体研发各列表页去掉新页面标签和标题后说明；基础服务去掉标题后的组件与能力说明。其余业务提示保留。

## 范围

- `console/src/views/agents/{AgentList,AgentGuard}.vue`
- `console/src/views/jobs/{JobBoard,JobBatches,JobRules}.vue`
- `console/src/views/services/SharedServices.vue`
- `console/src/views/trace/TraceList.vue`
- `console/src/views/eval/EvalView.vue`
- `console/src/views/models/ModelGateway.vue`
- `console/src/views/tools/ApprovalInbox.vue`
- `console/src/views/audit/AuditView.vue`
- `console/src/router/{index,nav,nav.test}.ts`
- `console/src/layouts/ConsoleLayout.vue`
- 本 spec、`sidebar-labels.md` 与 `docs/specs/r1014/ui-checklist.md`

对照 20261014 控制台原型，以本次用户截图说明覆盖历史标题；不修改接口、权限、业务流程及模型网关顺序。

## 验证

既有导航测试、生产构建（含 TypeScript 检查）、diff 空白检查；核对各模块路由标题、页面标题及详情面包屑。

验证结果：导航测试 2/2 通过，TypeScript 检查及生产构建通过，`git diff --check` 通过；八个模块主标题和路由标题已逐项核对。
