# 智能体目录按创建时间倒序

## 目标

智能体目录卡片按注册时间从新到旧排列。创建时间相同的按 `name` 升序。

## 依赖

- 前置任务：`GET /api/v1/agents` 已返回注册中心列表。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `listAgents`。不新增字段，不改契约文件。
- 依赖的外部组件：无。

## 改哪些文件

```
docs/specs/r1017/agent-list-created-desc.md
console/src/views/agents/AgentList.vue
keel-server/src/main/java/com/keel/server/registry/service/AgentRegistryService.java
keel-server/src/test/java/com/keel/server/registry/service/AgentListOrderTest.java
```

## 接口契约

`GET /agents` 的 `items` 按 `agent.created_at` 降序，相同时间再按 `name` 升序。`listAll()` 仍按名称升序。

## 界面对齐

- 原型位置：`docs/console/Keel-控制台前端-20261014.html` 的 `#/agents` 卡片列表。
- 对齐清单：无对应行，不改 `ui-checklist.md`。
- 页面数据来自 `GET /api/v1/agents`。目录拿到顺序后只做筛选和分页，不再反转。

## 实现要点

- 排序在 `AgentRegistryService.page`，分页发生在排序之后。
- 已删除（`removed`）和服务（`kind=SERVICE`）不进入这张列表。
- 目录前端删掉对整表的 `reverse()`。谱系仍用同一份已排序数据。

## 验收标准

```bash
mvn -o -pl :keel-server test -Dtest=AgentListOrderTest
cd console && npx vitest related src/views/agents/AgentList.vue
```

- [ ] 较新的 `created_at` 排在前面。
- [ ] 同一 `created_at` 按 `name` 升序。
- [ ] 服务和已删除记录不出现。

## 明确不做

- 不给 `AgentSummary` 增加 `createdAt`。
- 不改 `listAll()` 的名称序。
- 不改筛选、分页和卡片内容。
