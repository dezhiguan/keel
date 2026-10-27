# 任务成本协议 keel/v1（新增可选能力）

InvokeRequest.context.task_cost 为受信平台签发的可选对象，字段见 invoke.openapi.yaml。Python SDK 使用 ContextVar 传递到模型调用的 metadata.keel_cost_ticket，metadata.keel_cost_purpose 为 interactive/classify/analysis/memory。网关必须在转发厂商前移除这些字段；根任务与票据由平台/网关产生，不采信用户文本或模型输出。

GET /api/v1/agents/{name}/cost-control?env=staging 返回有效策略与当前接入覆盖。POST /api/v1/agents/{name}/cost-control/tasks 仅允许该智能体 SERVICE 身份创建受同环境资源绑定的运行任务；不得指定 owner、费用或策略。受信控制台入口在服务器内部创建用户所属任务。

GET /api/v1/agents/{name}/cost-control/tasks?env=staging 列出当前真实用户拥有的任务；SERVICE 只读自身agent/environment的任务。GET /tasks/{taskId} 返回同作用域实际已结算、未决预留、调用数、状态、策略和账单尝试。所有可读接口不返回ticket。POST /tasks/{taskId}/finish 为内部SERVICE完成上报，只接受状态与traceId，不接受费用。

POST /api/v1/cost-control/policies 仅平台ADMIN，body {scope:platform|org|env|agent, key:string, revision:integer, config:object}，revision执行CAS。config包含 mode:observe|enforce、maxCostCny、maxCalls、baseCostCny、perKInputCny、memoryBatchTurns、memoryMaxWaitSeconds、contextDedupe、evidenceDedupe。低层不得提高高层maxCostCny/maxCalls；已有任务保持策略快照。回退通过读取历史版本后以当前revision发布其config，保留版本历史。

网关管理接口 /admin/v1/task-costs/{open,finish,inspect} 只允许内部管理员凭据。open绑定alias、owner、group、snapshot及预算并返回随机ticket；SDK不能自行放宽。模型请求按网关生成requestId绑定持久任务；准入通过PostgreSQL根行锁原子完成，预留按请求输入及输出上限与允许降级路径估算。finish/inspect只读取真实llm_spend计算费用，不接受客户端申报结算。

memory claim的job可选costControl，worker选好实际新增范围后通过 /maintenance/cost 获取绑定租约及覆盖根任务的costContext；SDK维护调用继承。共享维护账单只归一次且按新覆盖用户轮次等额分摊；保留分摊规则与处理范围。失败/未知付费和日结恢复不能当零或制造逐次费用。

FinalEvent.meta.taskCost仅含taskId、mode、policyVersion、coverage，不含ticket。预算错误COST_BUDGET_EXCEEDED为429且不可自动重试；身份COST_SCOPE_DENIED为403；策略CAS/继承冲突COST_POLICY_CONFLICT为409。旧版没有上下文的运行明确标为未覆盖，不伪造费用0。

Service-only POST `/api/v1/agents/{name}/cost-control/tasks/{id}/bind` accepts env, taskId and traceId. The SDK binds the first trace before executing tools so background work resolves the original root without a completion race. Later child traces cannot overwrite it. Maintenance claim advertises `costControl`; `/memories/maintenance/cost` issues its private context after the worker selects the actual new prefix, not the broad claimed range.

Publishing enforce constraints updates the gateway alias live money/call ceilings. Existing task snapshots remain immutable, while each admission also applies tighter current emergency ceilings. Rollback never erases existing reservations or bills.
