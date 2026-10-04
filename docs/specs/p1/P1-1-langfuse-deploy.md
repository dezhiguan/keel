# P1-1 Langfuse Cloud（日本节点）

## 目标

追踪、评测、质量和提示词使用 Langfuse Cloud 日本节点 `https://jp.cloud.langfuse.com`。dev、staging、prod 三个项目在云端界面建好，密钥不进 git。智能体和 keel-server 只改地址和项目 Key 即可对接。

2026-10-04 已从云服务器 `8.163.30.216` 实测：项目接口 200，带 `x-langfuse-ingestion-version: 4` 的 OTLP span 能在 Tracing 页看到。

## 依赖

- 前置任务：无。不购买观测节点，不部署 ClickHouse。
- 依赖的契约文件：`contracts/trace-attributes.md`
- 依赖的外部组件及其真实行为：`.cursor/rules/external-apis.mdc` 的 Langfuse 一节，以及技术文档第 15 节 2026-10-04 的选型调整。

## 改哪些文件

```
deploy/langfuse/README.md
docs/specs/p1/P1-1-langfuse-deploy.md
```

`deploy/langfuse/` 里已有的自建 compose 不再使用。本任务把该目录收成一份云端接入说明，密钥放部署环境，不进 git。

## 接口契约

上报只走这一条：

```
POST https://jp.cloud.langfuse.com/api/public/otel/v1/traces
Authorization: Basic base64(public_key:secret_key)
x-langfuse-ingestion-version: 4
```

只支持 OTLP/HTTP（JSON 或 protobuf），不支持 gRPC。旧的 `/api/public/ingestion` 会在 2026-11-16 停掉 trace 写入，不要用。

读接口只允许：

```
GET /api/public/v2/observations
GET /api/public/v2/metrics
GET /api/public/v3/scores
GET /api/public/experiments
GET /api/public/experiment-items
GET /api/public/datasets
GET /api/public/dataset-items
```

`/api/public/traces`、`/observations`、`/scores`、`/metrics`、`/sessions`、`/dataset-run-items` 不要调用。2026-09-16 及之后创建的云端组织，`GET /api/public/traces` 返回 410。

## 实现要点

- **主机固定为日本节点。** 同一对 Key 打美国或欧盟节点会 401。`LANGFUSE_HOST=https://jp.cloud.langfuse.com`。
- **三个项目在云端界面手工创建。** 项目管理 API 是企业版，Keel 不替每个智能体建项目。智能体共用该环境的项目 Key，用属性 `keel.agent` 区分。三个环境项目已建好：`dev-keel`、`staging-keel`、`prod-keel`。试用项目 `My Project` 在界面里停用。
- **登录用邮箱密码。** Hobby 没有企业 SSO，不影响 SDK 上报和 keel-server 用 API Key 读数。auth-gateway 现在不是 OIDC 提供方，不要接单点登录。
- **在线评估器配在 observation 上**（根 agent 节点）。v4 不再跑 trace 级评估器。
- **Model Definitions 的单价与薄网关配置一致**（P1-2）。缺价格时追踪页上的费用会显示成 0。控制台金额以薄网关的人民币花费为准。
- **套餐按用途分开。** 当前账号是 Hobby：每月 5 万单位、保留 30 天、网页用户 2 个、Metrics API 每天 100 次。试点追踪用 Hobby 够用。P1-9 若每 5 分钟打一次 metrics，会超过每天 100 次，生产对账和总览轮询前升到 Core。追踪数据在 AWS 东京，审计仍在自有 PostgreSQL。

## 验收标准

```bash
# 在云服务器上，不经过本机 VPN
curl -sf https://jp.cloud.langfuse.com/api/public/health
# 用项目公钥:私钥打一条 OTLP/HTTP span，Tracing 页能看到 observation
```

- [x] `LANGFUSE_HOST` 是 `https://jp.cloud.langfuse.com`，仓库里没有项目私钥
- [x] dev、staging、prod 三个项目都已存在
- [x] 不带 `x-langfuse-ingestion-version: 4` 的请求在接入说明里写成已知坑
- [x] 客户端不调用 `GET /api/public/traces`
- [x] 接入说明写明 Hobby 的 metrics 每日上限，以及生产对账要升 Core

## 明确不做

- 不部署自建 Langfuse，不购买 8 核 32G 观测节点
- 不部署薄网关（P1-2）
- 不改 SDK 或 starter 的导出代码（P0-7、P0-12 已接 OTLP/HTTP）
- 不给每个智能体单独建 Langfuse 项目
