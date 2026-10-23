# LLM维护调用可选字段 · keel/v1

OpenAI兼容POST /v1/chat/completions的metadata对象可增加字符串keel_task，唯一已定义值为memory。它只描述调用用途，不是身份或授权；网关必须使用已验证虚拟Key的alias，不能使用metadata.caller_agent授权。未提供或其他值不触发维护配置。网关转发前移除keel_task，其余metadata保留。

运营request-defaults仍以alias / 实际模型id为范围。每个模型配置可增加memory对象，内部仅允许既有enable_thinking、preserve_thinking、thinking_budget、thinking.type选项，不允许继续嵌套memory或修改model/身份/密钥。仅匹配用途、alias、模型的chat请求使用该配置；应用前清除其他思考控制字段，避免互斥参数。普通请求维持既有缺省与显式值优先语义。Embeddings不应用维护配置。

Python ctx.llm.chat增加可选purpose="memory"，仅受治理模式携带标记。其他调用与独立模式不增加厂商字段。摘要及事实维护由SDK自动使用该可选用途。此扩展不修改消息、原文存储、输入token硬上限或现有计费语义。
