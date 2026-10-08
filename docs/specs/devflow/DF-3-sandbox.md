# DF-3 研发沙箱

## 目标

智能体生成的代码只进一次性沙箱 Job。keel-server 负责排队、建 Job、收回报告。沙箱不挂 Secret、不挂 ServiceAccount token，也不调用真模型。

## 依赖

- 前置任务：DF-2 账本。任务号必须已在 `devflow_job` 里。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 已有 `POST /devflow/sandbox/runs` 和 `GET /devflow/sandbox/runs/{runId}`。本任务不改契约。
- 外部组件：无。不调用 GitHub、薄网关或 Langfuse Cloud。包镜像源的地址仓库里没有，网络策略先不放行公网。

## 改哪些文件

```
docs/specs/devflow/DF-3-sandbox.md
docs/specs/devflow/README.md
deploy/k3s/sandbox/namespace.yaml
services/sandbox-sidecar/**
keel-server/src/main/resources/application.yml
keel-server/src/main/resources/db/migration/V11__devflow_sandbox.sql
keel-server/src/main/java/com/keel/server/devflow/**
keel-server/src/test/java/com/keel/server/devflow/SandboxServiceTest.java
keel-server/src/test/java/com/keel/server/devflow/SandboxJobTest.java
keel-server/src/test/java/com/keel/server/devflow/MemorySandboxStore.java
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
```

设计稿里的表版本停在 V8。V10 已是研发任务账本，沙箱运行记在 V11。

## 接口契约

沿用 DF-1。调用方必须是服务身份，而且只能是 `meta-agent`、`dev-agent`、`eval-agent`。其它服务身份返回 `403 DEVFLOW_GRANT_MISSING`。控制台用户返回 `403 AUTH_CONSOLE_FORBIDDEN`。

`repo` 只接受 `keel-agents` 组织下的仓库名，规则与 Git 服务相同：`^[a-z][a-z0-9-]{0,62}$`。`ref` 只接受 `refs/heads/` 下的分支，或 40 位小写提交号。不合规返回 `400 SERVER_INVALID_PARAM`。任务不存在返回 `404 SERVER_NOT_FOUND`。

`POST` 不阻塞到测试结束。未满 4 个正在跑的任务时返回 `RUNNING`，否则返回 `QUEUED`。`GET` 看到 Job 成功则为 `DONE`，失败则为 `FAILED`。报告按 UTF-8 截断到 64KB，`truncated=true`。从创建起超过 10 分钟仍未结束，状态写成 `TIMEOUT`，这次读取返回 `504 DEVFLOW_SANDBOX_TIMEOUT`。

## 实现要点

- 命名空间 `keel-devflow-sandbox`。ResourceQuota：requests 与 limits 都是 4 核 / 8Gi，最多 4 个 Pod。两个 keel-server 副本同时提交时，以配额为准。
- NetworkPolicy 拒绝入站，出站只放行 kube-system 的 53 端口。包镜像源地址未定，不写 `0.0.0.0/0`。
- Role 允许 keel-system 的 default ServiceAccount 在该命名空间创建、读取、删除 Job，并读取 Pod 日志。
- 单个 Job：`activeDeadlineSeconds=600`，`backoffLimit=0`，`automountServiceAccountToken=false`，`enableServiceLinks=false`。三个容器 limits 合计 1 核 / 2Gi。根文件系统只读，可写目录只有 emptyDir。不挂 Secret，环境变量里不放密钥。
- 假薄网关听 `127.0.0.1:8088` 的 `POST /v1/chat/completions`，假 Langfuse 听 `127.0.0.1:3000` 的 `POST /api/public/otel/v1/traces`。两者只在 Pod 内应答，不转发。
- runner 只确认两个假服务在应答，并把仓库名和 ref 写进报告。只读 deploy key 还没有单独的 Secret 名，本任务不克隆仓库。
- 收到终态日志后删除 Job，避免已结束的 Pod 占着 4 个名额。排队在下一次提交或读取时出队。
- 镜像名来自 `KEEL_SANDBOX_IMAGE`，缺省 `keel-sandbox:local`。这份清单不进现有的 keel-server 发布脚本。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=SandboxServiceTest,SandboxJobTest test
/Users/amy/CursorProject/keel/.venv/bin/pytest services/sandbox-sidecar/test_sidecar.py
```

- [ ] 非点名的服务身份不能开跑
- [ ] 仓库名或 ref 不合规被拒绝；任务不存在返回 404
- [ ] 第 5 个并发运行保持 `QUEUED`，前面的结束后才会建 Job
- [ ] 超过 64KB 的报告被截断；超过 10 分钟返回 `DEVFLOW_SANDBOX_TIMEOUT`
- [ ] 建出的 Job 不挂 Secret、不挂 ServiceAccount token，模型地址指向 127.0.0.1
- [ ] 假服务不引用厂商或 Langfuse Cloud 的地址

## 明确不做

- 在沙箱里跑 pytest、协议自检或克隆私有仓库
- 把 `sandbox.run` 写进工具注册表（DF-4）
- 读取 ResourceQuota 填共享服务页的用量（页面仍用占位）
- 放行具体的包镜像源
- 隐藏考题（DF-8）
