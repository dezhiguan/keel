# P1-19 GitHub 流水线部署

## 目标

推 `main` 后，GitHub Actions 按 askdb 同一条路把当前能跑的三件东西部署到 Server 3 的 k3s：`keel-llm`、`keel-server`、控制台。镜像进已有 ACR，SSH 经跳板机，复用 CareerMate 的登机凭据，不新增 GitHub Secret。

Langfuse 继续用云端日本节点。`deploy/litellm/` 与 `deploy/langfuse/docker-compose.yml` 不进这条流水线。

## 依赖

- 前置：P1-0 的 keel-server 与控制台、P1-2 的薄网关。
- 外部：与 askdb 相同的 `ACR_*`、`CAREERMATE_APP_SSH_KEY`、`CAREERMATE_APP_HOST`、`CAREERMATE_INGRESS_HOST`。

## 改哪些文件

```
.github/workflows/keel-cd.yml
ci/backend-gate.sh
ci/frontend-gate.sh
deploy/ci/configure-ssh.sh
deploy/ci/resolve-image.sh
deploy/k3s/backend.yaml
deploy/k3s/console.yaml
deploy/k3s/keel.yaml
deploy/README.md
docs/specs/p1/P1-19-github-cd.md
```

`.github/workflows/keel-ci.yml` 仍只跑全量测试，本任务不改它。

## 实现要点

- 镜像 tag 用 commit sha 前 12 位。CI 推 ACR 公网端点，k3s 从 VPC 端点拉。
- 清单里的镜像名是占位符，由流水线替换后再 `kubectl apply`。
- 控制台 NodePort `31110`（careermate 31080、ragforge 31090、askdb 31100）。`/api` 由控制台容器反代到 `keel-server`。
- `keel-llm`、`keel-server` 只用 ClusterIP。薄网关的 `/admin` 不对公网。
- 厂商密钥、管理密钥、Redis 地址、数据库口令放 k8s Secret，由人一次性创建，不进 GitHub，不进镜像。
- 日预算两个副本共用同一份 Redis。Secret 里没有 `redis-url` 时 Pod 不起。
- 库 `keel` 建在现有 PostgreSQL（内网 `172.25.90.183`）。不使用 `local` profile，不灌演示数据。
- 入口 nginx 片段准备好，合并进 rag-forge 之前不对外。流水线冒烟打节点上的 NodePort，不依赖还没做的 DNS。

## 验收标准

- 前后端分作业。后端清单是 `deploy/k3s/backend.yaml`，控制台清单是 `deploy/k3s/console.yaml`。
- pull request 跑后端测试、后端门禁、前端类型检查、前端测试、前端门禁和前端构建，不推镜像。
- 推 `main` 后三个镜像分开构建。后端和控制台各一次发布，两边都成功才冒烟。
- 节点上 `http://127.0.0.1:31110/` 返回控制台页面，`/api/v1/catalog` 的 `code` 为 `OK`。
- `keel-llm` Service 的 type 是 ClusterIP，`/health` 返回 `up`。

## 明确不做

- 不部署 keel-gateway、keel-audit、智能体。
- 不把花费明细改成落库（当前仍在进程内存，重启即丢）。
- 不签发证书、不改 rag-forge 的 nginx.conf。
