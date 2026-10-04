# Langfuse v4 compose（已作废）

2026-10-04 起追踪改用 Langfuse Cloud 日本节点 `https://jp.cloud.langfuse.com`，见 `docs/specs/p1/P1-1-langfuse-deploy.md`。不要再执行下面的 compose。

观测节点用这份 compose。没有高可用：web、worker、ClickHouse、Postgres、Redis、MinIO 都是单副本。

## 锁定的版本

| 镜像 | tag |
|---|---|
| `docker.langfuse.com/langfuse/langfuse` | `4.27.0` |
| `docker.langfuse.com/langfuse/langfuse-worker` | `4.27.0` |
| `clickhouse/clickhouse-server` | `25.12` |
| `redis` | `7.4.7` |
| `postgres` | `17.6` |
| `cgr.dev/chainguard/minio` | 官方 compose 未给 tag，这里保持原样，没有另猜一个版本 |

Python SDK 继续要求 `langfuse>=4.7.0`。

## 启动

```bash
cp .env.example .env   # 填密钥，不要提交
docker compose -f deploy/langfuse/docker-compose.yml config
docker compose -f deploy/langfuse/docker-compose.yml up -d
curl -sf "$LANGFUSE_HOST/api/public/health"
```

`LANGFUSE_HOST` 是内网地址。登录用 `.env` 里的邮箱和密码。`AUTH_DISABLE_SIGNUP=true`，不接 auth-gateway 单点登录。

上报必须：

```
POST /api/public/otel/v1/traces
Authorization: Basic base64(公钥:私钥)
x-langfuse-ingestion-version: 4
```

漏掉 `x-langfuse-ingestion-version: 4` 时服务不报错，数据大约 15 分钟后才可见。只支持 OTLP/HTTP，不支持 gRPC。

`GET /api/public/traces` 在 v4 返回 404。读数据用 `/api/public/v2/observations`。

## 三个项目

官方 `LANGFUSE_INIT_*` 在一次启动里只会创建 **一个** 组织、**一个** 项目和一对 Key。项目管理 API 是企业版，这里不调用。

`.env.example` 把这一个项目放成 `keel-dev`。staging、prod 两个项目不能靠再写两套同名变量变出来。观测节点落成之后，如果企业版接口仍不可用，就在内网界面手工建剩下两个项目，Key 放进部署机的密钥管理，不进 git。智能体不单独建项目，共用该环境的项目 Key，用 `keel.agent` 区分。

在线评估器挂在 observation（根 agent 节点）上。v4 不跑 trace 级评估器。

模型单价与 `deploy/litellm/config.yaml` 保持一致，见 P1-2。

## 备份和磁盘

```bash
# 每天一次。上传到对象存储的命令写在部署机的 cron 里，不写进仓库。
deploy/langfuse/backup.sh
# 使用率 >= 80% 时退出码 2
deploy/langfuse/disk-alert.sh
```

Compose 没有内置备份。备份脚本只负责打出当天的 Postgres 转储和 ClickHouse backup 目录。
