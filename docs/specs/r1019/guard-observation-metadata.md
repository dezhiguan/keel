# R1019 护栏已拦截但页面计数为零

trace 5dd8bf6dc1e70a494a4154358c0bc007 在 Langfuse 为 GUARDRAIL，实际 metadata 使用扁平 attributes.keel.agent / attributes.keel.status。页面只读直接键或嵌套 attributes，因而无法归属智能体，命中数0、链路为空。

兼容直接、扁平前缀及嵌套属性，直接值优先；状态、智能体、语义类型一致处理。保持查询最多100条，不翻全部历史。UI明确是近7天观测样本，不把样本当总数。真实云端响应已核对，未改变SDK上报字段或添加原文。
