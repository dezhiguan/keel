# R1019 逻辑模型账单与实时工具事件

## 页面复现

工具智能体正常调用 trace `281a901aaea25947911a75371ec00d2c` 的页面能看到 knowledge.search、devflow.catalog.search、shift.policy、提示词 v1 与生成回复，但模型为 chat-default，成本为「—」，网关尝试缺失。账单使用具体模型，现有代码按模型名相等关联，逻辑模型因此无法关联。多次同模型调用还会重复关联整条 trace 的账单。

同次调用调试 SSE 只有 evidence.checked 和 token / final，没有与实际工具节点对应的 tool 事件。页面证据位于 keel-qa-evidence-1019/trace-logical-model-cost-missing.png。

## 修复

- 网关为每个 HTTP 请求生成自己的唯一 requestId，响应头 X-Request-Id 原样返回；该 ID 与同一请求各次尝试的账单相同。客户端不得指定或复用 ID。
- Python SDK 把这个响应头写入已有 keel.llm.request_id 属性，不改变请求的逻辑模型名。
- console API TraceNode 增加可选 llmRequestId，TraceSummary 增加可选 pendingBillCount。按 requestId 关联 generation 与所有主请求 / 降级尝试；相同模型不能作为唯一关联依据。旧 trace 只有一个生成节点时允许无歧义兼容关联，多节点不猜测。
- trace 总成本和按智能体成本构成使用实际网关账单，待计费单独计数，不冒充零费用。节点上的网关尝试展示实际模型、状态、费用和现有价格 / 路由标记。
- SDK 在 loop 每步和工具返回时发送 step / tool 事件；复用现有追踪节点，避免新增重复工具 span。工具异常和挂起分别发送 failed / fallback 状态。

## 验证

用两个相同逻辑模型生成节点和不同请求 ID 的账单证明不会重复归账，覆盖一个请求两次不同模型尝试以及待计费。检查 SDK 流式和非流式请求 ID、实时工具成功与失败事件。部署后通过四个测试智能体的页面重新调用，核对实际模型、成本和事件。
