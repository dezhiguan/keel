# P1-9 在线探测与对账

## 目标

`ReconcileJob` 每 5 分钟对照登记状态、运行实例和流量，把问题写成 `reconcile_finding`。五种异常都能对上，正常情况不写 finding。

## 依赖

- 前置任务：P1-4。流量查询依赖 P1-1 的 Langfuse 与 P1-2 的 LiteLLM，测试用假 HTTP。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `Alert.kind`
- 依赖的外部组件：
  - Kubernetes Informer，标签 `keel.io/agent`、`keel.io/service`
  - Langfuse `GET /api/public/v2/metrics`，按 `keel.agent` 统计 trace 数
  - LiteLLM 按 `key_alias` 统计调用数
  - 两者取并集判断「7 天无流量」

## 改哪些文件

```
keel-server/src/main/java/com/keel/server/discovery/K8sAgentWatcher.java
keel-server/src/main/java/com/keel/server/discovery/ReconcileJob.java
keel-server/src/main/java/com/keel/server/integration/k8s/**
keel-server/src/test/java/com/keel/server/discovery/**
docs/specs/p1/P1-9-discovery-reconcile.md
```

`HeartbeatController`、`DifyProber`、`ManifestVersionChecker` 不在本任务实现（P1-10）。本任务只消费 `agent_instance` 上已有的 `last_seen_at`、`ready`、`version`。

## 接口契约

`reconcile_finding.kind` 只允许这五个值，与库表 CHECK 一致：

| 登记 | 运行 | 流量 | kind |
|---|---|---|---|
| ONLINE | 无就绪实例，或心跳超过 45 秒 | — | OFFLINE |
| 未登记 | 有带 keel 标签的 Pod | — | UNREGISTERED |
| ONLINE | 就绪 | 7 天无 | ZOMBIE |
| RETIRED | 仍在运行 | — | RETIRE_INCOMPLETE |
| 任意 | `/v1/manifest` 的版本 ≠ 登记版本 | — | VERSION_MISMATCH |

第六行是正常：ONLINE、就绪、有流量。不插入 finding。

已有未解决的同类 finding 不重复插入，更新 `detail`。条件消失后写 `resolved_at`。

## 实现要点

- **不要发明第六种 kind。** `TASKS.md` 写「六种 finding」，技术文档的场景表有六行，其中一行是正常。库表枚举只有上面五个。
- **UNREGISTERED 的 `agent_name` 不强制外键。** Pod 上的名字可能还没有 `agent` 行。P1-3 的表就是这样设计的。
- **流量取并集。** Langfuse 有 trace 或 LiteLLM 有调用，都算有流量。两个都失败时不要把智能体判成 ZOMBIE。
- **VERSION_MISMATCH 的版本来源本任务先读 `agent_instance.version`。** 真正去打 `/v1/manifest` 是 P1-10 的 `ManifestVersionChecker`。它还没写之前，实例版本为空则跳过这项，不要报错。
- Informer 只更新 `agent_instance`（`source=k8s`）。不在 watcher 里做五种判定，判定集中在 `ReconcileJob`，否则两处规则会分叉。
- 告警文案可以写成 `Alert`，但推送企业微信不在本任务。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] 上表五种输入各产生一条对应 kind，正常输入产生零条
- [ ] 同一智能体同一 kind 的未解决 finding 只有一行
- [ ] 条件恢复后 `resolved_at` 非空
- [ ] Langfuse 和 LiteLLM 都不可用时，没有新的 ZOMBIE
- [ ] 测试不连接真实集群

## 明确不做

- 不实现心跳接口、Dify 探活、manifest 拉取（P1-10）
- 不在总览页展示这些 finding（P1-14 读表）
- 不自动下线，只记录
