# P1-22 调试对话 Markdown 排版

## 问题与范围

`/agents/:name/debug` 将智能体回复作为纯文本插值，导致粗体、列表和代码围栏直接显示 Markdown 标记。对照 `docs/console/Keel-控制台前端-20261014.html` 的 P2-18 调试入口及 `r1014/ui-checklist.md`，保留现有三栏、会话、链路和 SSE 行为，仅改回复展示。

## 实现

- 使用 markdown-it 解析智能体历史回复和实时流式回复，用户消息与错误消息继续按原文显示；存储与接口不变。
- 独立 Markdown 组件提供段落、标题、强调、列表、引用、链接、代码和表格样式，沿用控制台主题。长链接换行，代码及宽表格局部横向滚动。
- 禁止原始 HTML，保留解析器的危险链接过滤；链接在新页打开并设置 `noopener noreferrer`；图片只显示可读替代文字，避免回复自动加载远程资源。
- 使用同一解析路径处理未闭合代码围栏等 SSE 中间状态，完成后与历史回复一致。

## 文件

- `console/package.json`、`console/package-lock.json`
- `console/src/components/MessageMarkdown.vue`
- `console/src/utils/markdown.ts`、`console/src/utils/markdown.test.ts`
- `console/src/types/markdown-it.d.ts`（渲染器使用的 API 类型）
- `console/src/views/agents/AgentDebug.vue`
- 本 spec 及 `docs/specs/r1014/ui-checklist.md`

## 验收

- 截图中的有序列表和粗体正常排版，无裸露 `**`；段落间距明确。
- 多段落、嵌套列表、引用、链接、代码块、表格及普通文本可读；实时与历史回复一致。
- 原始 HTML、事件属性、危险 URL 无法执行；用户原文不被解析。
- 前端测试及生产构建通过，并在浏览器验证组件的实际排版和窄容器溢出。

## 验证记录

- 前端全量 27 个测试文件、212 项测试通过，含 14 项 Markdown 回归及实际 Vue 组件服务端渲染。
- `npm run build` 通过（TypeScript 检查与 Vite 生产构建）。
- 当前会话的沙箱无法启动 Chromium（启动后 SIGTRAP）；实际页面截图及窄容器视觉验收留待部署后复验，不以服务端渲染替代浏览器验收。
