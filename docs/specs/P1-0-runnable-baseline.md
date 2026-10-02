# P1-0 底座最小可运行：keel-server + console 骨架

## 目标

本机 `docker compose` 起 PG，`keel-server` 用 local profile 启动，`console` 用 `npm run dev` 启动后，浏览器打开 `http://localhost:5173` 能看到控制台外壳（侧边菜单九个入口），总览页和智能体列表页的数据来自 keel-server 查 PG。其余七个页面先是占位页。

## 依赖

- 前置任务：P0-4（monorepo 骨架，已完成）
- 依赖的契约文件：`contracts/console-api.openapi.yaml`（`/me`、`/insight/overview`、`/agents`；`Envelope`、`ApiError`、`PageMeta`、`AgentSummary`、`Overview`、`CurrentUser`）、`contracts/error-codes.yaml`
- 依赖的外部组件：无。auth-gateway、Langfuse、LiteLLM 本任务都不接。

## 改哪些文件

```
docs/specs/P1-0-runnable-baseline.md
contracts/error-codes.yaml                      # 新增 SERVER_* 通用错误码（只加条目，非破坏）
pom.xml                                         # 编译开 -parameters（父 pom 不是 spring-boot-starter-parent，默认没开）
keel-common/src/main/java/com/keel/common/error/**
keel-server/pom.xml
keel-server/src/main/java/com/keel/server/{common,config,registry,insight}/**
keel-server/src/main/resources/**
keel-server/src/test/**
console/**
README.md                                       # 本地启动说明
```

## 接口契约

摘自 `contracts/console-api.openapi.yaml`，以原文件为准：

- 成功：`{code: "OK", message, traceId, data}`；失败：`{code, message, traceId, retryable}`
- `GET /api/v1/me` → `CurrentUser`
- `GET /api/v1/insight/overview?env&range` → `Overview{kpi, agents, alerts, costByAgent}`
- `GET /api/v1/agents?env&category&status&q&page&size` → `PageMeta + items: AgentSummary[]`

表结构按技术文档第 06 节，本任务只建 registry 用到的三张：`agent`、`agent_version`、`agent_instance`。其余九张表留给 P1-3。

## 实现要点

- 种子数据放 `db/seed-local/`，只有 local profile 把它加进 Flyway `locations`，生产迁移里没有假数据。
- `application.yml` 的数据源只读环境变量 `KEEL_DB_URL / KEEL_DB_USERNAME / KEEL_DB_PASSWORD`，不写默认值；本机的连接串只在 `application-local.yml`。
- `insight` 只能调 `registry` 的 service，不能用 registry 的 mapper（ArchUnit 拦）。
- 错误码：Java 侧先手写 `com.keel.common.error.ErrorCode` 枚举，与 `error-codes.yaml` 同名同 retryable；P0-2 代码生成落地后替换。
- 前端所有请求走 `src/api/http.ts`；接口类型由 `openapi-typescript` 从契约生成到 `src/api/schema.d.ts`，不手写 DTO。
- Vite dev server 把 `/api` 代理到 `http://localhost:8080`。

## 验收标准

```bash
docker compose -f deploy/local/docker-compose.yml up -d
mvn -pl :keel-server -am test
mvn -pl :keel-server spring-boot:run -Dspring-boot.run.profiles=local
curl -s localhost:8080/api/v1/agents | jq .data.total        # > 0
cd console && npm install && npm run build && npx vitest run
npm run dev                                                   # 打开 http://localhost:5173
```

- [ ] `mvn -pl :keel-server test` 通过（含 ArchUnit、Testcontainers PG 下的接口测试）
- [ ] 总览页 KPI 的智能体总数、在线数与库里一致
- [ ] 智能体列表可按状态、关键字筛选，可分页
- [ ] 九个菜单都能点开，不报错

## 明确不做

- 鉴权：不接 auth-gateway，不加 Spring Security。`/me` 返回固定开发用户，留 `TODO`。
- `category`、`template`、调用量、成本、评分等需要 manifest 解析或外部数据的字段，返回 `null`，留 `TODO`。
- 告警、成本分布返回空数组。
- 智能体详情抽屉、新建向导、其余七个页面的内容。
- keel-gateway、keel-audit、SDK 不动。
