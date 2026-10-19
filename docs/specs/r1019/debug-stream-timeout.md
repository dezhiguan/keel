# R1019 调试SSE的30秒异步超时

普通调试再次出现 Failed to obtain JDBC Connection；同一时刻 keel-server 日志明确有 AsyncRequestTimeoutException，且错误处理器试图把ApiError JSON写进已经设为text/event-stream的响应，触发No converter并截断流。Hikari连接失效和请求取消同时发生，不能仅凭此断言数据库本身原因已解决。

AgentEndpointClient 对上游调用的时限是3分钟，MVC未配置异步时限，容器30秒提前终止。将MVC异步时限设为210秒，保留上游3分钟边界让正常错误路径先结束；不增加模型/评测60秒读超时、不增加预算或重试。SSE异步超时异常返回符合契约的event:error字符串；其他JSON错误显式application/json，避免内容协商二次500。

验证：模拟已有SSE响应上的异步超时，必须返回可解析error帧；定向Java验证和页面长调用/停止复测。连接池问题仍需继续观察，不因超时修复直接标记已解决。
