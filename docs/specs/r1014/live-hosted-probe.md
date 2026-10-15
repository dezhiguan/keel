# 线上缺口：托管页入口与探测健康

## 目标

`https://keel.ragforge.net/a/{agent}` 和 `/a/widget.js`、`/a/miniprogram.js` 由 keel-server 返回；模型探测成功后，另一个网关副本的健康状态也是「已探测」。

## 依赖

- 前置任务：R4-4、R4-8。
- 依赖的契约文件：无新字段。
- 依赖的外部组件及其真实行为：薄网关已用 Redis 在副本之间共享虚拟 Key 和账单。探测集合沿用同一 Redis。

## 改哪些文件

```
docs/specs/r1014/live-hosted-probe.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
deploy/docker/console-nginx.conf
keel-llm/src/main/java/com/keel/llm/ProbeMarks.java
keel-llm/src/main/java/com/keel/llm/Gateway.java
keel-llm/src/main/java/com/keel/llm/LlmConfiguration.java
keel-llm/src/test/java/com/keel/llm/GatewayTest.java
keel-llm/src/main/java/com/keel/llm/LlmController.java
keel-server/src/main/java/com/keel/server/devflow/MetaAgentExecution.java
```

## 接口契约

无新接口。`GET /a/{agent}`、`GET /a/widget.js`、`GET /a/miniprogram.js` 仍由 keel-server 提供。`POST /admin/v1/models/{name}/probe` 成功后把模型名写入 Redis 集合 `keel-llm:probed`。

## 界面对齐

- 原型位置：智能体详情「渠道发布」；模型与成本「模型与渠道」健康列。
- 对齐清单里的行：渠道发布 tab；模型与渠道健康。
- 页面数据来自哪个 keel-server 接口：渠道列表与托管 HTML 走 keel-server；健康来自 `GET /api/v1/insight/costs`。

## 实现要点

- 控制台 nginx 的 `location /` 会把未知路径回落到 `index.html`。`/a/` 必须单独反代到 keel-server，且写在这条兜底之前。
- 探测标记不能只放在进程内存。未配置 Redis 时仍用本进程集合，测试可以让两个 Gateway 共用同一个 `ProbeMarks`。
- 非流式对话超时 45 秒时，岗位代码生成会被上游掐断并记成 500。对话改为 3 分钟；向量化仍是 45 秒。失败日志带上异常消息。

## 验收标准

```bash
mvn -o -pl :keel-llm test
```

- [ ] `console-nginx.conf` 中 `/a/` 反代到 keel-server，不再回落 `index.html`。
- [ ] 两个 Gateway 共用探测集合时，一次探测后两边的健康都是 `PROBED`。

## 明确不做

- 不改托管页 HTML、不改 Cookie 路径、不把探测记成真实调用（`LIVE`）。
- 不在本任务里跑生产发布、审批恢复或七天观察。
