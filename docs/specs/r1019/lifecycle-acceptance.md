# R1019 四个代码智能体生命周期验收

## 范围

以 `docs/console/Keel-优化效果-20261018.html` 为验收目标。在生产控制台通过代码开发向导登记四个独立智能体；手工代码开发阶段复用受治理的 QA 运行服务，其他步骤通过页面完成。每个智能体都使用平台基准库、只读共享工具、版本化提示词和真实评测。

| 智能体 | 专项 |
| --- | --- |
| qa-tools-1019 | 工具授权、技能版本、知识引用 |
| qa-guard-1019 | 正常输入、入口与材料注入、护栏事件和零模型成本 |
| qa-memory-1019 | SSE 增量、停止、刷新历史、跨副本与会话隔离 |
| qa-routing-1019 | chat-cheap 逻辑模型、路由版本、预算与限流、成本和账单 |

## 交付和检查

- 新增四份 QA profile，模型默认使用页面选择的逻辑模型；现有 1017 profile 行为保持一致。
- 部署清单从 profiles.json 读取名称，避免登记和实际部署列表分离。
- 新增各 50 条非空、不同的评测输入，覆盖知识、工具、缺参、无证据、注入、成本和专项场景；使用真实回答评分。
- 四个新智能体代码清单的门禁分数为 0.95、最大回归 0.02，逻辑模型允许两个具体模型作为降级目标；在正常的代码登记阶段同步清单，页面评测必须读到这些实际门禁参数。
- 先记录页面复现证据，再修复产品问题；新增错误码或跨语言字段先改契约。
- 页面重新自检、提示词保存及生效、评测和门禁、发布、真实调用和追踪均需分别核验。未观察到的步骤不能标为通过。

## 初始观察（2026-10-19）

- 登录成功，代码向导能选逻辑模型、同组织知识库和工具。
- qa-tools-1019、qa-guard-1019、qa-memory-1019 登记后均显示可恢复草稿，资源已开通，独立服务未部署导致健康检查 ConnectException；页面明确允许部署后重新自检。属于代码开发必要步骤。
- 本机 DNS 不可用，但经 DNS over HTTPS 得到解析地址后，浏览器保持原域名访问成功；此项属于测试环境问题。

## 页面执行记录

- 四个智能体均已登记，代码提交 `d7aa503`，CD 运行 `39217965448`。
- 四个智能体均经页面「重新自检」通过：健康、协议、追踪、审批绑定全部为通过。
- 四份 chat 提示词均通过页面保存 v1 并在 dev 生效，变量 question、temperature=0.1、max_tokens=1200。
- 通过页面发布 qa-cite-1019 v1，再编辑另存 v2，差异和旧版只读展示正常；工具智能体通过页面授权 v2，运行代码锁定 v2。
- qa-tools-1019 首次普通调试出现 `SERVER_INTERNAL_ERROR / Failed to obtain JDBC Connection`，没有模型 final；重试成功，答复引用 shift.policy、devflow.catalog.search、qa-cite-1019 v2。成功 trace `281a901aaea25947911a75371ec00d2c`。连接错误尚未稳定复现，继续观察，不能标成已修复。
- qa-memory-1019 首次记住代号「海鸥1019」并返回咨询口径及知识引用，含增量 token 和 final，trace `65c35820591981c0a956071c537c5d6b`。
- qa-routing-1019 用 chat-cheap 返回咨询口径，含引用、增量 token 和 final，trace `fc6aa07799ee5a577cd9d58023352f92`；仍需从账单核对实际模型与路由版本。
- 护栏 enforce 未拦截且写入会话的问题已修复并随 aa842ac 部署；真实请求返回 guardrail 与 GUARD_INJECTION_BLOCKED，trace 95f1a453e4a05c2da6fa2083739c22c7，刷新后零消息。实时事件展示另受会话切换竞态影响，继续修复。
- 页面 RPM / TPM / 并发、渠道勾选当前只读，无法完成优化稿要求的页面配额调整；路由和限额专项仍在执行。

## 本地验证边界

护栏修复的 SDK、契约、loop、提示词、评测、门禁、技能及 QA 运行服务相关测试已通过。扩跑整个 Python 目录发现现有 standalone 和过期 FakeLlm 签名测试失败；在未修改的基线 checkout 单独运行同样失败，不能把它们当成本次修复通过的证据。Java 两侧契约与护栏、SSE 存储测试已通过。线上复测完成后再更新最终状态。

## 第二轮修复验证（待部署页面复测）

配额编辑、requestId 精确账单关联、实时工具事件与会话切换锁已完成。本地 SDK 相关测试 101 项、Java 定向测试 67 项、前端 21 项通过，前端类型检查与后端清单门禁通过。此结果不代替线上完整生命周期验收。

## 67418a8 部署后页面复测

- CD 39225258641 全部通过。
- 将真实会话创建请求延迟 2.5 秒，输入和发送均禁用；完成后可以调用，护栏错误及实时事件保留。trace 5dd8bf6dc1e70a494a4154358c0bc007。证据 session-race-retest.json/png。
- qa-memory 刷新后准确回忆「海鸥1019」，trace 55c50856e6f498f6e4b2c6d82d5bca6b；新会话明确不知道另一会话代号，trace d3238d0f7dcf48cb5e547b9c0dedcdbd。证据 memory-retest.json。
- qa-tools 新会话含 tool=3、step=3、token=45、final=1，回答使用 shift.policy 咨询结果和技能三段结构。trace 16db5fa2474cf4d0eb5313a4f4414caa，整条成本 ¥0.0049；generation 请求模型 chat-default、实际 qwen3.8-flash，节点成本 ¥0.0025、路由版本和 SETTLED 账单可见。证据 tools-after-cost-events.json/png、trace-cost-after.png。
- 四个智能体提示词 v1 经页面在 test 和 staging 生效，均 HTTP 200；staging 门禁为 pending，未冒充已通过。证据 prompt-promotions-test-staging.json。
- 各50条用例导入完成，qa-routing 部分成功后的重试新增7条，qa-tools成功新增50条。
- 新发现评测刷新丢失进度、阻塞式评测实际串行、历史问题混入当前输入三项，见对应 spec。后续部署 206b6dd 包含修复，复测未完成。
