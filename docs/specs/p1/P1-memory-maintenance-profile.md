# P1 记忆维护调用配置

真实SDK13验收显示事实提取35秒超时，回复能使用近期原文，但确认尚未持久化，不算验收通过。不能继续单纯提高超时。

增加keel/v1可选调用标记metadata.keel_task=memory。SDK仅在摘要/事实维护时通过ctx.llm发送；用户业务调用保持原参数。网关按真实认证Key别名和实际路由模型读取request-defaults中的可选memory子配置，在转发时移除内部标记，覆盖思考相关选项；不改变路由、Key授权、价格、日预算、输入上限或其他智能体。

仅给keel-assistant-staging的两个已授权模型配置维护非思考模式：Qwen enable_thinking=false，DeepSeek thinking.type=disabled。参数来源已核对官方文档：https://www.alibabacloud.com/help/en/model-studio/deep-thinking 与 https://api-docs.deepseek.com/guides/thinking_mode/ 。厂商差异只在网关运营配置，SDK不写模型/厂商名，不直连模型。仍使用共享60秒记忆期限、35秒事实提取上限和10000 token硬检查。

先改契约，再改SDK和网关。验证正常请求与其他Key/模型/embeddings不受影响，伪造caller_agent不能套用别名配置，内部标记不发到厂商，非法嵌套配置拒绝启动。正式发布SDK14并锁定助理；网关配置/代码先部署，再升级助理。真实会话必须核对PG已确认/被替代状态与原文引用，而不只看回答。
