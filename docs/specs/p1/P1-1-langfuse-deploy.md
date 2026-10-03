# P1-1 观测节点与 Langfuse v4

## 目标

内网能访问一套锁定版本的 Langfuse v4。无界面初始化已经建好 dev、staging、prod 三个项目和各自的 API Key。每天备份到对象存储，磁盘占用有告警。

## 依赖

- 前置任务：无代码依赖。机器（ECS，同 VPC）由人准备，不在本仓库里买。
- 依赖的契约文件：`contracts/trace-attributes.md`
- 依赖的外部组件及其真实行为：以 `.cursor/rules/external-apis.mdc` 和 `docs/architecture/Keel-技术文档.html` 第 15 节为准。不要按 Langfuse v3 写。

## 改哪些文件

```
deploy/langfuse/**
docs/specs/p1/P1-1-langfuse-deploy.md
```

`deploy/langfuse/` 现在只有 `.gitkeep`。密钥不进 git。

## 接口契约

上报只走这一条：

```
POST {LANGFUSE_HOST}/api/public/otel/v1/traces
Authorization: Basic base64(public_key:secret_key)
x-langfuse-ingestion-version: 4
```

只支持 OTLP/HTTP（JSON 或 protobuf），不支持 gRPC。

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

`/api/public/traces`、`/observations`、`/scores`、`/metrics`、`/sessions`、`/dataset-run-items` 在 v4 返回 404，compose 和后续客户端都不要配它们。

## 实现要点

- **镜像版本写死在 compose 里**，不要用 `latest`。部署当天选最新稳定版，把版本号写回本文件末尾。Python SDK 锁 `langfuse>=4.7.0`，和这次部署的服务端对应。
- **三个项目用无界面初始化创建**，不要手点界面。项目管理 API 是企业版，不要写成 Keel 替每个智能体建项目。智能体共用该环境的项目 Key，用属性 `keel.agent` 区分。
- **登录用邮箱密码**，`AUTH_DISABLE_SIGNUP=true`。auth-gateway 现在不是 OIDC 提供方，不要接单点登录。
- **在线评估器配在 observation 上**（根 agent 节点）。v4 不再跑 trace 级评估器。
- **Model Definitions 的单价与 `deploy/litellm/config.yaml` 一致**（P1-2）。缺价格时 Langfuse 和 LiteLLM 都会把成本记成 0。
- 备份脚本每天把 ClickHouse / Postgres 数据卷拷到对象存储。磁盘使用率告警阈值写在同一份部署说明里。Compose 没有高可用，不要在文件里假装有。

## 验收标准

```bash
# 在观测节点上
docker compose -f deploy/langfuse/docker-compose.yml config
curl -sf "$LANGFUSE_HOST/api/public/health"
# 用项目公钥:私钥打一条 OTLP/HTTP span，15 分钟内能在对应项目里看到
```

- [ ] compose 文件里的镜像 tag 是具体版本，不是 `latest`
- [ ] dev、staging、prod 三个项目都已存在，仓库里没有它们的私钥
- [ ] 不带 `x-langfuse-ingestion-version: 4` 的请求被记为已知坑，部署说明里写明必须带
- [ ] `GET /api/public/traces` 返回 404，`GET /api/public/v2/observations` 不是 404
- [ ] 备份脚本能跑完一次，磁盘监控有告警规则

## 明确不做

- 不部署 LiteLLM（P1-2）
- 不改 SDK 或 starter 的导出代码（P0-7、P0-12 已接 OTLP/HTTP）
- 不给每个智能体单独建 Langfuse 项目
