# Keel 部署

与 askdb 同机：Server 3 的 k3s，经跳板机 SSH。推 `main` 触发 `.github/workflows/keel-cd.yml`。

本轮只上三件：

| 工作负载 | 副本 | 对外 |
|---|---|---|
| keel-console | 1 | NodePort 31110，页面和 `/api` |
| keel-server | 1 | 仅集群内，由控制台反代 |
| keel-llm | 2 | 仅集群内。`/admin` 不对公网 |

Langfuse 用 `https://jp.cloud.langfuse.com`。不要部署 `deploy/litellm/` 和 `deploy/langfuse/docker-compose.yml`。

## GitHub

复用已有 Secret，不新增：

| Secret | 用途 |
|---|---|
| `ACR_REGISTRY` / `ACR_USERNAME` / `ACR_PASSWORD` | 镜像仓库。Secret 里存 VPC 地址，流水线推公网、k3s 拉内网 |
| `CAREERMATE_APP_SSH_KEY` | Server 3 私钥 |
| `CAREERMATE_APP_HOST` | Server 3 地址 |
| `CAREERMATE_INGRESS_HOST` | 跳板机，缺省 `8.163.63.222` |

镜像 tag 是 commit sha 前 12 位。

## 一次性准备

数据库在数据机的现有 PostgreSQL 上，走内网。这台实例的超级用户是 `ragforge`。

```bash
ssh root@8.163.30.216 "docker exec -i ragforge-postgres \
  psql -U ragforge -d postgres" <<'SQL'
CREATE ROLE keel LOGIN PASSWORD '<口令>';
CREATE DATABASE keel OWNER keel;
SQL
```

Redis 用数据机上那套，库号 `3`（askdb 占用 `2`）。两个薄网关副本必须共用这一条地址，日预算才是同一份。

在 Server 3 上建 Secret。密钥不进 Git 和 GitHub。

```bash
kubectl create namespace keel-system

kubectl -n keel-system create secret generic keel-llm \
  --from-literal=admin-key="$(openssl rand -base64 32)" \
  --from-literal=redis-url='redis://:口令@172.25.90.183:6379/3'

kubectl -n keel-system create secret generic keel-llm-vendors \
  --from-literal=QWEN_API_KEY='...' \
  --from-literal=DEEPSEEK_API_KEY='...'

kubectl -n keel-system create secret generic keel-server \
  --from-literal=KEEL_DB_URL='jdbc:postgresql://172.25.90.183:5432/keel' \
  --from-literal=KEEL_DB_USERNAME='keel' \
  --from-literal=KEEL_DB_PASSWORD='<口令>'
```

三个 Secret 缺一，对应 Pod 会停在 `CreateContainerConfigError`，rollout 超时。这是有意的。

单价写在 `keel-llm` 的 `application.yaml` 里，随镜像走。改价要发一版。缺单价或写成 0，进程拒绝启动。

## 流水线

一条流水线，前后端分开：

- 后端：测试、门禁，通过后分别构建 `keel-llm`、`keel-server`、`keel-gateway`、`keel-audit`，再应用 `deploy/k3s/backend.yaml`
- 前端：类型检查、测试、门禁，通过后构建，再构建镜像，应用 `deploy/k3s/console.yaml`
- 两边都发布完才冒烟

pull request 只跑测试和门禁，不推镜像。推 `main` 才构建和部署。

入口文件 `deploy/nginx-keel.conf` 要合并进 rag-forge 的 `nginx.conf` 之后才会有 `keel.ragforge.net`。证书还没签发，合并前先在 Server 2 上 `nginx -t`。

## 验证

```bash
curl -sf http://127.0.0.1:31110/ | head
curl -sf http://127.0.0.1:31110/api/v1/catalog
kubectl -n keel-system get svc keel-llm -o jsonpath='{.spec.type}'   # ClusterIP
```

花费明细还在进程内存里，Pod 一重启就没了，两个副本各记各的。日预算计数在 Redis 里，这部分两个副本共用。

## 回滚

```bash
kubectl -n keel-system rollout undo deployment/keel-llm
kubectl -n keel-system rollout undo deployment/keel-server
kubectl -n keel-system rollout undo deployment/keel-console
```
