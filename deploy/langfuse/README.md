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

## Cloud Japan 中继（2026-10-13 线上修复）

Keel Pod 的 `jp.cloud.langfuse.com` 通过 hostAliases 指向数据机
`172.25.90.183:443`，中继只转发 TLS 字节，不解密、不保存项目 Key。
源码在 `cloud-relay.py`，安装路径 `/usr/local/sbin/keel-langfuse-relay.py`，
由 `keel-langfuse-relay.service` 管理。不要另起手动监听进程，否则 systemd
会因为端口冲突反复重启。每个上游 IP 的连接超时为 2 秒，失败后尝试下一个地址。
中继读取 TLS ClientHello 的 SNI，仅允许 Langfuse Japan 和 api.github.com，
后者供 git-ci 使用同一私网 443 端口。端到端证书校验和加密保持有效。

验收：从 Keel Pod 访问 `https://jp.cloud.langfuse.com/api/public/health` 返回 200，
同时确认 `systemctl is-active keel-langfuse-relay.service` 为 active，重启计数不再增长。
