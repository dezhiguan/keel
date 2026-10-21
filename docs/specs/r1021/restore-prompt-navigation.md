# R1021 恢复提示词管理入口

## 目标

恢复此前十项清单遗漏的提示词模块，观测分组按提示词管理、测评中心、链路追踪排序。标题为「提示词管理」，小字为「版本管理与调试发布」。页面标题、路由标题和详情面包屑同步名称。保留模型网关位于工具与技能之前。

## 范围

修改 `console/src/router/{nav,nav.test,index}.ts`、`console/src/layouts/ConsoleLayout.vue`、`console/src/views/prompts/PromptList.vue` 及 `docs/specs/r1014/ui-checklist.md`、`docs/specs/r1021/sidebar-labels.md`。本次纠正覆盖历史 spec 中不单列提示词的约定；原型参照 20261014 控制台的提示词入口，业务功能不变。

## 验证

运行既有导航测试、生产构建（含 TypeScript 检查）和 diff 空白检查。

验证结果：调整排序后的导航测试 2/2 通过，生产构建及 TypeScript 检查通过，`git diff --check` 通过。
