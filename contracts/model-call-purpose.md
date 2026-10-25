# keel/v1 可选模型调用purpose

既有metadata.caller_agent不变。metadata.keel_task可选枚举memory、interactive，由受控SDK调用设置，不从用户文本获得。网关按已认证虚拟Key alias和实际上游model选取同名scoped request-defaults；没有匹配项沿用模型默认。发送厂商前去掉keel_task。

Python LlmClient.chat(purpose=None|memory|interactive)，Context.loop(model_purpose=None|interactive)为新增可选参数。Java SDK现有通用metadata对象兼容，无新增必填JSON字段。analysis未标记interactive时不受交互式推理配置影响。上游推理参数仍只允许既有白名单。
