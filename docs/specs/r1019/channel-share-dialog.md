# 分配份额弹窗对齐优化稿

## 问题和边界

Key 与配额的分配份额弹窗按模型只读列表展示，并带日预算和「已允许的模型」。优化稿是「允许的渠道（只列同环境）」：勾选同环境渠道，每个渠道单独填 RPM，旁边是 TPM 和并发，下面是路由覆盖和份额合计。日预算不在这个弹窗里。

不把历史模型名（如 `qwen-plus`、`chat-default`）画成渠道，也不把整把 Key 共用的 RPM 标成渠道份额。

## 接口

`POST /admin/v1/keys/{alias}/budget` 和 `POST /insight/costs/keys/{alias}/budget` 增加可选 `channels: [{id, rpm}]`。

- 出现 `channels` 时，平台管理员才能提交。至少一条。渠道必须存在，且环境包含这把 Key 的环境。
- `rpm` 为正整数。厂商上限不为空时，各 Key 在该渠道上的 RPM 合计不能超过上限。
- 保存后这把 Key 允许的具体模型改为所选渠道的模型，并记下每渠道 RPM。Key 级共用 RPM 清空，避免旧上限盖住新份额。TPM、并发仍按原规则。未传 `dailyBudgetCny` 时日预算不动。
- 已保存份额的请求，按该渠道 RPM 在进上游之前拒绝，返回 `LLM_RATE_LIMITED`，不改试别的渠道。没有份额记录的 Key 仍走原来的 Key 级 RPM。

## 页面

- 文案是「允许的渠道（只列同环境）」。复选框和 RPM 可改。勾上时 RPM 先填 50。
- TPM、并发并排。路由未覆盖和份额合计用优化稿的两段提示。超出厂商上限或没有渠道时不能保存。
- 不展示日预算滑杆，也不展示「已允许的模型」。
- Key 列表的渠道列显示渠道名和该渠道 RPM，不再把逻辑模型名当成渠道。

## 验收

```bash
mvn -o -pl :keel-llm -Dtest=GatewayTest test
mvn -o -pl :keel-server -Dtest=KeyQuotaEditTest test
cd console && npx vitest related src/views/models/channelShare.ts src/views/models/ModelGateway.vue --run
```
