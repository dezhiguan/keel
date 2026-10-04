# Langfuse Cloud 日本节点（P1-1）

追踪用云端，不部署本目录里的 compose。

- 主机：`https://jp.cloud.langfuse.com`
- 同一对项目 Key 打美国或欧盟节点会 401
- dev、staging、prod 三个项目在云端界面创建。项目管理 API 是企业版，Keel 不自动建项目
- 项目私钥放部署环境，不进 git
- 登录用邮箱密码。当前 Hobby 没有企业 SSO，不影响 API 上报

上报：

```
POST /api/public/otel/v1/traces
Authorization: Basic base64(公钥:私钥)
x-langfuse-ingestion-version: 4
```

漏掉 `x-langfuse-ingestion-version: 4` 时服务不报错，数据大约 15 分钟后才可见。只支持 OTLP/HTTP，不支持 gRPC。不要用 `/api/public/ingestion`，云端会在 2026-11-16 停掉 trace 写入。

2026-09-16 及之后创建的组织，`GET /api/public/traces` 返回 410。读数据用 `/api/public/v2/observations`。

Hobby 限额：每月 5 万单位，保留 30 天，网页用户 2 个，Metrics API 每天 100 次。每 5 分钟打 metrics 会超限。生产对账和总览轮询前升 Core。追踪数据在 AWS 东京。

2026-10-04 已实测三个环境项目都能读写：`dev-keel`、`staging-keel`、`prod-keel`。主机是日本节点，OTLP 带 `x-langfuse-ingestion-version: 4` 后，observations 接口能读到 span。试用项目 `My Project` 可以在界面里停用。项目私钥只放部署环境，不进 git。

`docker-compose.yml` 是作废的自建草稿，不要执行。
