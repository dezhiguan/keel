# P2-19 评测中心跳到 Langfuse

## 目标

评测中心页头有「在 Langfuse 中打开」，新标签打开这次门禁对应的 Langfuse 数据集实验页。前端不自己拼地址。

## 依赖

- 前置：现有 `GET /eval/{agent}/latest`。`LANGFUSE_HOST`、`LANGFUSE_PROJECT_ID` 与链路页同一套。
- 深链形态（Langfuse 控制台路由，2026-10-10 按仓库页面路径核实）：`{host}/project/{projectId}/datasets/{datasetId}/experiments`。数据集名字常带 `/`，路径里只用数据集 id。没有 id 时落到 `{host}/project/{projectId}/datasets`。
- 主机或项目 id 为空时不返回 `langfuseUrl`，按钮不出现。

## 改哪些文件

```
docs/specs/p2/P2-19-eval-langfuse-link.md
contracts/console-api.openapi.yaml
keel-server/src/main/java/com/keel/server/insight/EvalQueryService.java
keel-server/src/test/java/com/keel/server/insight/EvalQueryServiceTest.java
console/src/api/schema.d.ts
console/src/views/eval/EvalView.vue
console/src/mocks/data/eval.ts
docs/console/Keel-控制台前端.html
docs/architecture/Keel-技术文档.html
```

## 验收

```bash
mvn -o -pl :keel-server test -Dtest=EvalQueryServiceTest
cd console && npm run gen:api && npm run typecheck
```

- [ ] 有评测记录且配了 Langfuse 项目时，按钮打开实验页
- [ ] 没配 `LANGFUSE_HOST` 或 `LANGFUSE_PROJECT_ID` 时不显示按钮
