# P2-12 共享服务页接入 rag-forge

## 目标

控制台共享服务页的 rag-forge 卡片显示知识库清单、近 24 小时检索、今日模型花费，以及进程内分段耗时均值。查不到的数字保持 null。

## 依赖

- 前置任务：P1-14、P1-18
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `SharedServices`
- 依赖的外部组件及其真实行为：
  - rag-forge `GET /actuator/keel`，Basic Auth 用户 `metrics-reader`。返回近 24 小时检索、P50/P95（毫秒，窗口内没有成功检索时为 null）、今日 `model_usage_daily` 花费、知识库清单。不返回文档原文。缺这个接口时对应字段为 null。
  - rag-forge `GET /actuator/prometheus`，同一套 Basic Auth。分段耗时取 `ragforge_retrieval_latency_seconds` 的 sum/count，这是均值，不是分位。现网指标没有 `caller_agent` 时调用方列表为空。
  - rag-forge `GET /actuator/health` 不需要登录，用来判断在线。
  - 集群里没有 Prometheus 查询服务。`up{job="rag-forge"}` 不能再拿来填 P95。
  - 薄网关已有别名 `rag-forge-{env}` 时，今日花费改用该 Key 的 `spentCny`。没有这把 Key 时用 rag-forge 自己的日花费。

## 改哪些文件

```
docs/specs/p2/P2-12-shared-services-ragforge.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/services/SharedServices.vue
keel-server/src/main/java/com/keel/server/insight/SharedServiceMonitor.java
keel-server/src/main/java/com/keel/server/integration/ragforge/**
keel-server/src/main/java/com/keel/server/integration/prometheus/PrometheusExposition.java
keel-server/src/test/java/com/keel/server/insight/SharedServiceMonitorTest.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
deploy/k3s/services/keel-server.yaml
```

rag-forge 仓库另加 `GET /actuator/keel`，不在本仓库。

## 接口契约

`GET /api/v1/insight/services` 的 `SharedServices` 不变。`stageLatency[]` 增加可选字段 `meanMs`、`basis`（`p50` 或 `mean`）。

## 实现要点

- 地址用 `RAGFORGE_BASE_URL`，口令用 `RAGFORGE_METRICS_PASSWORD`。口令为空时不编造数字。
- 能列出 `ragforge-backend` 的 Endpoints 时，分段耗时把各副本的 sum/count 加总后再除。列不出就打 Service 一次。
- 近 24 小时没有检索时，`searches24h` 为 0，P50/P95 为 null。不要把进程启动以来的计数填进 24 小时。
- 知识库超过 30 天未更新标 `stale`。`recall@5` 评测摘要没有数时为 null。
- 前端在数字为 null 时显示「—」，不要拼出单独的 `s` 或 `¥`。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] 假 rag-forge 返回的知识库、花费和分段均值出现在 `/insight/services`
- [ ] 地址为空或连不上时，P95 为 null，不返回假健康
- [ ] 请求里没有发往 `/api/public/traces` 的调用

## 明确不做

- 不把检索评测台迁进控制台
- 不用管理员破玻璃去调 rag-forge 的用户接口
- 不改检索公式，不改向量维度
