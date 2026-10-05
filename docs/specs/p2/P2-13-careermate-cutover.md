# P2-13 CareerMate 接入底座

## 目标

控制台能看到 CareerMate，并从底座对话入口调用它。CareerMate 增加 Keel 的 `/v1/health`、`/v1/manifest`、`/v1/invoke`，底座按集群地址把它登记成智能体。

## 依赖

- 前置任务：P0-11 的 `/v1/invoke` 形状。CareerMate 现有会话和模型调用保持原样。
- 依赖的契约文件：`contracts/sse-events.schema.json` 的 `final` 事件。
- 依赖的外部组件及其真实行为：CareerMate 后端 Service `careermate-backend.careermate.svc.cluster.local:18080`。调用仍走它自己的会话和模型，不在本任务改厂商地址。

## 改哪些文件

```
docs/specs/p2/P2-13-careermate-cutover.md
keel-server/src/main/java/com/keel/server/registry/service/CareerMateRegistrar.java
keel-server/src/main/java/com/keel/server/registry/service/AgentChatService.java
keel-server/src/main/java/com/keel/server/integration/agent/AgentEndpointClient.java
keel-server/src/test/java/com/keel/server/registry/service/CareerMateRegistrarTest.java
deploy/k3s/services/keel-server.yaml
console/src/api/agents.ts
```

CareerMate 仓库只加协议适配，不删现有观测和检索客户端：

```
backend/src/main/java/com/careermate/keel/**
backend/src/test/java/com/careermate/keel/**
deploy/k8s/careermate/backend-deployment.yaml
```

## 实现要点

- `/v1/invoke` 收 `{input:{text}}`，转成 CareerMate 一次会话，把最后一条 `agent` 回复包成 `event: final`。
- 该入口不走用户登录。两边用同一个 `KEEL_CAREERMATE_TOKEN` 请求头 `X-Keel-Bridge`。没配或对不上就拒绝，避免 NodePort 上匿名烧模型。
- 底座只在环境变量 `KEEL_CAREERMATE_ENDPOINT` 非空时登记。登记只写 `agent` 和 `agent_version`，不跑开通流程。
- 对话超时放到 60 秒。控制台默认 15 秒不够一次求职对话。

## 验收标准

```bash
mvn -pl :keel-server test -Dtest=CareerMateRegistrarTest,AgentEndpointClientTest
```

CareerMate 仓库：

```bash
mvn -pl backend -Dtest=KeelRepliesTest test
```

- [ ] 没配 endpoint 时不写库。
- [ ] manifest 里的 endpoint 就是配置的地址。
- [ ] 多条消息时取最后一条 agent 回复。

## 明确不做

- 不删 CareerMate 的 `observability`、`RagForgeClient`、本地审计表。
- 不把模型调用改到薄网关，不把提示词迁到 Langfuse。
- 不新建评测集。
