# 元智能体护栏检测认证与状态显示

## 问题与范围

元智能体 `/v1/guard/check` 受既有 BridgeAuth 保护，控制台转发未发送 X-Keel-Bridge，导致 HTTP401。保留运行端认证，在 keel-server 使用已有 KEEL_META_AGENT_TOKEN，仅向 meta-agent 的登记运行端发送认证头；其他智能体不携带该凭据。只读检测不进入业务，不调用模型、工具或写记忆/审计。

页面对未声明 pii 的清单缺省显示 mask，但 SDK 缺省 off。页面改为如实显示 off，显式 mask 不变。已对照10月14日原型及现有护栏检测交互，不新增交互或契约字段。

本次先核实当前两个实例的真实配置和正常/攻击输入，修复检测与显示。建议元智能体后续启用 indirectInjection=enforce、canary=enforce，并按输出需求显式设置 pii=mask；taintedTool 保留 approve。配置升级应另行候选评测，不直接改写已发布v2快照。

## 验收

- 受保护的模拟运行端：meta-agent检测带正确认证，其他智能体不收到元智能体凭据；无效认证仍明确失败。
- 页面缺省PII为off、显式mask不变。
- 线上两个实例：不带认证401，合法认证正常输入clear、攻击输入blocked；工具/知识来源显示实际部署模式。
- 提交推送后等待服务端CI/CD，走与控制台相同的检测API复测，不通过放开运行端鉴权处理。
