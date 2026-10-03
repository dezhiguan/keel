# P1-10 心跳、Dify 探活与版本核对

## 目标

非 K8s 实例能用心跳续约。超过 45 秒没有心跳的实例不再算就绪。Dify 智能体靠探活更新实例。运行中的 `/v1/manifest` 版本与登记版本不一致时，实例上的版本会被写成实际值，供对账标成 VERSION_MISMATCH。

## 依赖

- 前置任务：P1-9
- 依赖的契约文件：`contracts/invoke.openapi.yaml` 的 `GET /v1/manifest`、`contracts/error-codes.yaml`
- 依赖的外部组件：智能体自己的 `/v1/health` 与 `/v1/manifest`。Dify 的探活 URL 来自该实例登记的 endpoint。Dify 管理 API 的具体路径在实现时对照当时部署的 Dify 版本，对不上就停下来写进本文件，不要套通用 REST 猜。

## 改哪些文件

```
keel-server/src/main/java/com/keel/server/discovery/HeartbeatController.java
keel-server/src/main/java/com/keel/server/discovery/DifyProber.java
keel-server/src/main/java/com/keel/server/discovery/ManifestVersionChecker.java
keel-spring-boot-starter/src/main/java/com/keel/starter/heartbeat/**
sdk-python/keel/heartbeat.py
docs/specs/p1/P1-10-heartbeat-probe.md
```

SDK 侧只在 `liveness: heartbeat` 时发送。K8s 存活探针不走这条接口。

## 接口契约

```
POST /api/v1/instances/heartbeat
{ agent, env, instanceId, version }
```

成功 204。未知智能体返回 `SERVER_NOT_FOUND`。

`agent_instance.source` 取值：`k8s`（P1-9）、`heartbeat`（本接口）、`probe`（DifyProber）。

45 秒没有新的 `last_seen_at`，该行 `ready=false`。阈值不要散落成魔法数，和 `GW_AGENT_OFFLINE` 的说明一致。

## 实现要点

- **心跳不创建智能体户口。** 只更新已登记智能体的实例行。未登记的 Pod 仍由 P1-9 记 UNREGISTERED，不要靠心跳把户口补上。
- **版本核对写的是实例上的 version，不改 `agent_version`。** 登记版本仍是发布记录。两者不同由 P1-9 报 VERSION_MISMATCH。
- **核对请求失败时不要把版本改成空。** 空版本会被 P1-9 跳过，从而掩盖一次网络故障。失败保持上次版本，并在日志里带 `trace_id` 和 `agent`。
- Dify 探活只覆盖 `runtime=dify` 的实例。代码智能体不走探活。
- starter 与 Python SDK 的心跳间隔必须小于 45 秒，建议 15 秒。不要在 SDK 里写 keel-server 的地址默认值；地址来自环境变量，缺失则不启动心跳。

## 验收标准

```bash
mvn -o -pl :keel-server test
pytest sdk-python/tests -q -k heartbeat
```

- [ ] 心跳后 `ready=true`，`source=heartbeat`，`last_seen_at` 更新
- [ ] 45 秒无心跳后 `ready=false`，P1-9 的对账能据此得到 OFFLINE
- [ ] `/v1/manifest` 返回的版本与登记不同时，实例 version 被更新为运行版本
- [ ] manifest 请求失败时实例 version 保持原值
- [ ] `liveness!=heartbeat` 的 Python 智能体不会发心跳

## 明确不做

- 不改五种 finding 的判定表（P1-9）
- 不实现网关的 `GW_AGENT_OFFLINE` 拦截（P2-6 读的是同一份就绪数据）
- 不解析 Dify 应用内部节点
