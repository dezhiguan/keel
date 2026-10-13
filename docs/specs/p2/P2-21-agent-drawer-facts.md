# P2-21 智能体抽屉：自检、绑定资源、实例

## 目标

智能体详情抽屉的接入自检、绑定资源和「实例与资源」显示登记数据里已经存在的事实。标识用开通时真正创建的名字，不把智能体名填进每一行。

## 依赖

- 前置任务：P1-4 详情、P1-6 资源账本、K8s watch 写入 `agent_instance`
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `AgentDetail`、`SelfCheckReport`。不改契约。
- 外部组件：薄网关别名是 `{agent}-{env}`，Secret 名是 `keel-{agent}`，数据集名是 manifest 的 `spec.eval.dataset`。已在集群上核对 chat-demo 与 meta-agent。

## 改哪些文件

```
docs/specs/p2/P2-21-agent-drawer-facts.md
keel-server/src/main/java/com/keel/server/provisioning/**
keel-server/src/main/java/com/keel/server/registry/**
keel-server/src/main/java/com/keel/server/discovery/K8sAgentWatcher.java
keel-server/src/test/java/com/keel/server/provisioning/ProvisioningRollbackTest.java
keel-server/src/test/java/com/keel/server/registry/model/AgentDetailTest.java
keel-server/src/test/java/com/keel/server/discovery/ReconcileJobTest.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
console/src/views/agents/agentDrawer.ts
console/src/views/agents/agentDrawer.test.ts
console/src/views/agents/AgentDetail.vue
```

## 实现要点

- 自检八行对照原型。只把账本、manifest、实例就绪能证明的项标成通过或失败。健康探针、协议探针、追踪没有落库结果时保持未探测，不写毫秒数，不写 SSE 5/5。
- 评测门禁读该智能体最近一次已完成的 `eval_run`。有总分就写成 `0.90 ≥ 0.85`（阈值用结果里的 `gate.minScore`，缺省 0.85），通过与否跟结果的 `passed`。没有完成的评测仍是未运行。打开详情不新跑评测。
- 旧账本把四种资源的 `external_id` 都写成了智能体名。读的时候改成真实标识；已经写对的（例如 `meta-agent-dev`）保持原值。新开通也写入真实标识。
- 实例行用 Pod 名。发现仍是旧的 Pod UID 时，下一次 watch 删掉那一行。
- 资源状态在控制台翻成原型用词：有效、已写入、已导入、已注册、已吊销、已删除。
- 知识库和工具只来自 manifest。manifest 里没有就继续显示「—」。

## 验收标准

```bash
mvn -o -pl :keel-server test -Dtest=AgentDetailTest,ProvisioningRollbackTest,ReconcileJobTest,CostServiceTest
cd console && npx vitest run src/views/agents/agentDrawer.test.ts
```

- [ ] chat-demo 这种旧账本能显示 `chat-demo-dev`、`keel-chat-demo`、`chat-demo/smoke`
- [ ] 没有探针结果的自检项不是通过
- [ ] Pod 名进入实例列表

## 明确不做

- 打开详情时不重跑健康检查和五次协议探针
- 不改 `agent_resource` 表结构，不回填历史行
- 不为没有知识库或工具的智能体补原型里的示例名字
