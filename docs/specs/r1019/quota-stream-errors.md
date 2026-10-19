# R1019 流式配额拒绝返回500

页面设置 qa-routing-1019-dev RPM=1，首轮成功，第二轮返回 SERVER_INTERNAL_ERROR「薄网关 HTTP 500」，trace 8e103918d276fcaab501005e77b08beb。网关日志确认 Admission 正确抛出 LLM_RATE_LIMITED，但 Controller 已设 text/event-stream，异常处理器返回 JSON Map 无转换器，最终500。

修复调用前拒绝的错误响应显式 JSON Content-Type，保留429、注册错误码及 trace_id。SDK 对429保留网关明确的 LLM_RATE_LIMITED / LLM_BUDGET_EXCEEDED 等现有错误码；无可识别错误码的上游429沿用 GW_QUOTA_EXCEEDED。不得增加429重试或降级。

TPM 当前按已完成请求的真实 token 用量计数，首个请求可超出配置，需继续验证后续拒绝；未获得证据前不能把首轮放行单独记为限流无效。测试设置已全部恢复为空、预算30。

验证：真实MVC请求 Accept=text/event-stream+stream=true在限额拒绝时得到429 JSON；SDK流式非流式保留错误；页面RPM、TPM后续请求、并发拒绝后再恢复正常调用。
