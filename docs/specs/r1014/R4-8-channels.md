# R4-8 渠道发布

## 目标

控制台能发布托管页面、网页嵌入和网页 SDK 渠道。未登记的域名进不去。托管页面拿不到控制台会话 Cookie。

## 依赖

- 前置任务：keel-gateway 的 `/agents/{name}/v1/invoke` 仍是对话入口。本任务发的是渠道发布凭证，不是厂商密钥。
- 依赖的契约文件：`contracts/error-codes.yaml` 的 `CHANNEL_ORIGIN_DENIED`；`contracts/manifest.schema.json` 的可选 `spec.channels`；`contracts/console-api.openapi.yaml`。
- 依赖的外部组件及其真实行为：换票仍走 auth-gateway 已有接口。小程序流式方案不在本任务。

## 改哪些文件

```
docs/specs/r1014/R4-8-channels.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/error-codes.yaml
contracts/manifest.schema.json
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/api/channels.ts
console/src/views/agents/AgentDetail.vue
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
keel-common/src/main/java/com/keel/common/model/AgentManifest.java
sdk-python/keel/_generated/errors.py
sdk-python/keel/_generated/manifest.py
keel-server/src/main/resources/db/migration/V23__channel.sql
keel-server/src/main/resources/static/a/widget.js
keel-server/src/main/java/com/keel/server/auth/ConsoleCookies.java
keel-server/src/main/java/com/keel/server/channel/ChannelOrigin.java
keel-server/src/main/java/com/keel/server/channel/ChannelService.java
keel-server/src/main/java/com/keel/server/channel/ChannelController.java
keel-server/src/main/java/com/keel/server/channel/HostedPage.java
keel-server/src/test/java/com/keel/server/auth/ConsoleAuthRulesTest.java
keel-server/src/test/java/com/keel/server/auth/ConsoleLoginTest.java
keel-server/src/test/java/com/keel/server/channel/ChannelOriginTest.java
keel-server/src/test/java/com/keel/server/channel/ChannelFlowTest.java
keel-server/src/test/java/com/keel/server/archunit/ArchitectureTest.java
```

## 接口契约

表 `channel`、`channel_credential`。身份是 `EXCHANGE`、`OPENID`、`ANONYMOUS`。

- 控制台 Cookie：`HttpOnly`、`SameSite=Strict`、`Path=/api`。不再用 `__Host-` 前缀，因为该前缀要求 `Path=/`，托管页 `/a/{agent}` 就会带上会话。
- `GET /a/{agent}` 托管页，不写 Cookie。
- `POST /agents/{name}/channels` 发布并返回嵌入代码。
- `POST /agents/{name}/channels/entry` 校验域名。未登记返回 `CHANNEL_ORIGIN_DENIED`。
- 网页脚本 `/a/widget.js` 负责对话、识别 `event: suspend`、点赞点踩。

## 界面对齐

- 原型位置：智能体详情渠道发布 tab。
- 对齐清单：渠道列表、嵌入代码、托管页面、发布到新渠道。小程序行保持推迟。
- 页面数据来自 `GET/POST /agents/{name}/channels`。

## 实现要点

- 匿名渠道在 manifest 有 `risk: high` 工具时拒绝发布。
- `OPENID` 只登记，状态是 `REGISTERED`，不实现小程序流式。
- 托管页 HTML 不出现 `keel_console` 或 `document.cookie`。

## 验收标准

```bash
python3 scripts/codegen.py && .venv/bin/python scripts/check_contracts.py
mvn -o -pl :keel-common -DskipTests install
mvn -o -pl :keel-server -Dtest=ChannelOriginTest,ChannelFlowTest,ConsoleAuthRulesTest,ArchitectureTest test
cd console && npx vitest related src/views/agents/AgentDetail.vue --run
```

- [x] 未登记域名返回 `CHANNEL_ORIGIN_DENIED`。
- [x] 会话 Cookie 带 `Path=/api` 和 `HttpOnly`。
- [x] 托管页响应没有 `Set-Cookie`，正文没有控制台 Cookie 名。

## 明确不做

- 不做小程序 SDK，不核实小程序流式方案。
- 不实现完全定制的独立前端项目。
- 不在渠道凭证里放厂商 API Key。
