# 可选意图与路由证据（keel/v1）

`intent-routing.schema.json` 定义代码生成的 `final.meta.routing` / `error.meta.routing` 可选对象。未提供时现有消费者行为不变；调用方传入的同名对象不参与执行授权，模型输出的 metadata 必须由运行时代码覆盖。schema 从同一来源生成 Java IntentRouting 与 Python 模型，两端消费 contracts/tests 的相同正反例。

意图说明目标、operation、复杂度、证据需要和不确定性。slots 仅允许 env、agent_id、task_id、当前材料引用和摘要；不得含 query、材料原文、身份声明或票据。模型分类所需原文只留在 generation 输入输出，不进入路由审计。

plan.mode 统一 local_fast/local_slow/remote/queue/clarify/deny/approval。计划是候选执行路径，不是业务状态或权限；实际调用在 actual_calls 中另记。clarify/deny/approval 不带 executor。steps 限16项，ID唯一，depends_on必须存在且无环；部分执行须满足独立性和用户条件。SDK在结构验证后额外检查图约束。

Phase A 的 assistant_staging 策略只执行原来已授权的本地工具和自身分析任务。remote/并行不能仅凭此schema启用。请求/步骤幂等、目标能力与环境/版本校验、长任务授权、查询取消、预算预留仍是B/C独立运行时门槛。

LlmClient.chat 新增可选 response_schema：仅供已确认支持 JSON Schema 的网关/模型配置，发送标准 response_format，禁止业务工具，同时强制 emit_tokens=False并验证返回JSON、枚举和完整性。未知字段、截断、拒答、工具调用和非有限数拒绝。分类调用仍须有界超时和预算，SDK不把支持该参数当成任何模型都支持。现有调用默认不变。
