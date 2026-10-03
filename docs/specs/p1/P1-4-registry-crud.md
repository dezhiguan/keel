# P1-4 registry：查询与登记数据

## 目标

控制台能按环境、分类、状态、关键字分页查询智能体，能打开详情。名称检查和 manifest 预览可用。登记数据只写 `agent`、`agent_version`、`agent_instance`，不调用任何外部系统。

## 依赖

- 前置任务：P1-3（其余业务表可以还没建；本任务只用 V1 的三张表）
- 依赖的契约文件：`contracts/console-api.openapi.yaml`（`/agents`、`/agents/{name}`、`/agents/name-check`、`/agents/manifest-preview`、`/catalog`）、`contracts/manifest.schema.json`、`contracts/error-codes.yaml`
- 依赖的外部组件：无

## 改哪些文件

```
contracts/error-codes.yaml
keel-server/src/main/java/com/keel/server/registry/**
keel-server/src/test/java/com/keel/server/registry/**
docs/specs/p1/P1-4-registry-crud.md
```

已有空类和 `GET /api/v1/agents` 在原文件上补，不要另起一套 controller。

## 接口契约

摘自 `contracts/console-api.openapi.yaml`，成功体是 `{code:"OK", message, traceId, data}`：

```
GET  /api/v1/agents
GET  /api/v1/agents/{name}
GET  /api/v1/agents/name-check?name=
POST /api/v1/agents/manifest-preview
GET  /api/v1/catalog
```

`GET /agents` 已实现状态和关键字筛选。本任务补上 `env`、`category`。

名称规则与 CLI 相同：`^[a-z][a-z0-9-]{1,38}[a-z0-9]$`。冲突或格式不对时在 `error-codes.yaml` 增加条目后再用，不要写字面量错误码。

## 实现要点

- **详情从 manifest 快照读。** `manifestYaml`、`knowledgeBases`、`tools`、`models`、`delegates`、`manifestHash` 来自该环境下最新的 `agent_version.manifest_json`。没有版本就这些字段为空，不要猜。
- **调用量、成本、评分、P95 本任务返回 null。** 它们来自 Langfuse / LiteLLM，在 P1-14 填。`instances` 只统计 `agent_instance` 里 `ready=true` 的行。
- **`selfCheck`、`resources`、`findings` 本任务为空。** 自检是 P1-5，资源是 P1-6，对账是 P1-9。
- **`category` 不在 V1 表上。** 不要改 V1。分类从 manifest 快照或列表查询时的派生规则读；规则写不清楚就在 spec 实现时把字段留 null 并写 TODO，不要为了筛选去改已经执行过的迁移。
- **预览只生成 YAML，不写库，不注册。** `POST /api/v1/agents`、发布、下线不在本任务。
- `insight` 仍然只能调 registry 的 service，不能用 registry 的 mapper。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

新增依赖后第一次去掉 `-o`。本机没有 Docker 时 Testcontainers 用例按仓库约定跳过，CI 照常跑。

- [ ] `env=prod`、`category=biz`、`status`、`q` 可以组合筛选并分页
- [ ] 不存在的 name 返回 `SERVER_NOT_FOUND`
- [ ] `name-check` 对已占用名称返回 `available:false`，对非法名称同样返回 `available:false` 且 `reason` 非空
- [ ] `manifest-preview` 的 YAML 能通过 `manifest.schema.json`
- [ ] 源码里没有新的字面量错误码

## 明确不做

- 不实现 `POST /api/v1/agents` 的开通流程（P1-6）
- 不实现 `POST /releases`、`POST /retire`（发布是 P2-11，下线回收在 P1-6 只留接口位，本任务不调用 LiteLLM / auth-gateway）
- 不改 V1 迁移
