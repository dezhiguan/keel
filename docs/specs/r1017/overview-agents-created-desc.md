# 运行总览智能体表按创建时间倒序

## 目标

运行总览「智能体」表按注册时间从新到旧排列。创建时间相同的按 `name` 升序。

## 依赖

- 前置任务：`GET /insight/overview` 已返回 `agents`。目录接口 `GET /agents` 已按创建时间倒序。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `getOverview`。不新增字段。
- 依赖的外部组件：无。

## 改哪些文件

```
docs/specs/r1017/overview-agents-created-desc.md
contracts/console-api.openapi.yaml
keel-server/src/main/java/com/keel/server/registry/service/AgentRegistryService.java
keel-server/src/main/java/com/keel/server/insight/OverviewService.java
keel-server/src/test/java/com/keel/server/registry/service/AgentListOrderTest.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
```

## 接口契约

`GET /insight/overview` 的 `agents` 按 `agent.created_at` 降序，相同时间再按 `name` 升序。`listAll()` 仍按名称升序，调用量接口继续用它。

## 界面对齐

- 原型位置：`docs/console/Keel-控制台前端-20261014.html` 的 `#/overview` 智能体表。
- 对齐清单：无对应行，不改 `ui-checklist.md`。
- 页面数据来自 `GET /api/v1/insight/overview`。筛选和分页保持接口返回的顺序。

## 实现要点

- 排序在 `AgentRegistryService.listNewestFirst`。总览只换这一次读取。
- 已删除（`removed`）和服务（`kind=SERVICE`）不进入这张表。已下线（`RETIRED`）仍由总览自己滤掉。
- 前端不另排一次。

## 验收标准

```bash
mvn -o -pl :keel-server test -Dtest=AgentListOrderTest
```

- [ ] 较新的 `created_at` 排在总览表前面。
- [ ] 同一 `created_at` 按 `name` 升序。
- [ ] `listAll()` 仍按名称升序。服务和已删除记录不出现。

## 明确不做

- 不给 `AgentSummary` 增加 `createdAt`。
- 不改 `listAll()` 和 `GET /agents` 的既有顺序。
- 不改筛选、分页和表格列。
