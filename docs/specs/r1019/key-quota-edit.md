# R1019 Key 配额页面可配置

## 问题和边界

页面 qa-routing-1019-dev 的配额弹窗把 RPM、TPM、并发全部禁用，无法完成优化稿中的配额设置和限额生效验证。薄网关已支持 Key 级限额；本次让平台管理员通过页面设置它们。三个限额均为整把 Key 共用，不声称已有每渠道独立 RPM。渠道授权仍按已有注册配置。

## 接口

在现有 console `POST /insight/costs/keys/{alias}/budget` 和薄网关 `POST /admin/v1/keys/{alias}/budget` 增加可选 rpm、tpm、concurrency：缺省保持原值，null 取消限制，正整数设置上限。预算仍遵循现有滑杆范围。保存不轮换 token，不改模型授权和 Key 版本。网关先验证全部字段，再一次保存。

先更新 console-api 契约，Java 控制台服务透传到薄网关；这些字段的修改仅平台管理员可提交。旧的仅预算请求保持兼容。

## 页面和验收

- 弹窗标明 Key RPM 跨渠道共用，RPM、TPM、并发可编辑；空白表示不限制。
- 非正整数明确报错，页面关闭重开及刷新显示网关实际值。
- 在 qa-routing-1019-dev 上设置小额限额，通过两个页面会话验证真实拒绝和恢复，不改其他 Key。
- 本地验证跨网关副本读取同一 Key 的新限额、令牌不变、旧预算请求不清空限额；线上验证 RPM / TPM / 并发并清回原值。

涉及 console API 和模型页、CostService / InsightController / LiteLlmClient、薄网关 Gateway / LlmController 及其针对性测试、外部接口说明和 UI 清单。
