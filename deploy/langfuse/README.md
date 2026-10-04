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

2026-10-04 已从云服务器 `8.163.30.216` 实测：项目接口 200，带上述请求头的 OTLP span 能在 Tracing 页看到。当时只有试用项目 `My Project`，三个环境项目仍要在界面里建。

`docker-compose.yml` 是作废的自建草稿，不要执行。
