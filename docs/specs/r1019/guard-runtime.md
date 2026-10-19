# R1019 护栏实际执行

## 复现与范围

2026-10-19 在 qa-guard-1019 尚未部署时，页面把输入注入设为 enforce 后点击检测，显示「已拦截，返回 GUARD_INJECTION_BLOCKED（SSE 发 event: error）」「在 未部署 执行」。请求实际只执行 keel-server 的正则，不能证明运行实例按配置拦截。Python SDK 的 guard/injection.py 仍为 TODO，asgi 入口没有执行输入检查。证据截图位于本机 keel-qa-evidence-1019/guard-probe-without-runtime.png。

运行服务部署并通过自检后，调试页发送同一输入仍产生 167 个事件、模型普通拒答和 final，写入两条会话消息。真实 trace 为 `abe626075d8dfd4592f078a3597cb74e`，截图 guard-enforce-before-fix.png。模型拒答不能代替平台入口拦截。

修复范围：契约、Python SDK、keel-server 护栏转发及控制台说明和结果，相关回归测试及 UI 对齐清单。新建的验收智能体使用真实 SDK 验证，禁止用测试智能体自行拒答替代平台护栏。

## 约定

- manifest 增加可选 indirectInjection、taintedTool、canary，先改契约并生成 Java/Python 模型。
- SSE 增加可选 guardrail 事件，字段仅含检查、来源、模式、是否命中和动作，不含用户原文。
- 输入检测在 agent entry 之前运行；enforce 命中发 guardrail 和 error，不进入业务、不调用模型、不写会话。
- 工具返回和知识片段在进入模型前检测；enforce 剔除并标记污染，之后高风险动作按配置挂起审批或拒绝。
- canary 只在内部系统上下文存在，输出检测防止标记泄露；含污染内容的调用不写记忆。
- POST /v1/guard/check 在运行实例只读执行同一检测规则，不调用模型、工具、记忆和审计。keel-server 代理该接口；未部署或运行实例不可读时页面显示失败，不伪造拦截结果。
- 保存的注册配置须经部署才能影响运行实例；检测结果显示实际实例身份与实际模式。
- 注入评测的 expectedOutput 可声明 errorCode；只接受该用例期待的确切 SSE 错误码，普通拒答和其他错误均不能视作拦截成功。CLI 与集群评测均保留 metadata.tags 的安全维度。

## 验收

通过实际页面检测及调试验证正常输入、shadow、enforce、工具与知识来源、SSE guardrail/error、账单零调用及记忆不落库。离线回归证明入口早于业务、命中不调模型，工具及知识注入不进入可信系统上下文。Python SDK 和 starter 的契约测试使用相同新增样例。

本地：74 条相关 Python 测试、44 条 Java 护栏 / SSE / 两侧契约测试、13 条前端调试和护栏测试通过，控制台类型检查通过。待部署后更新页面复测结论。
