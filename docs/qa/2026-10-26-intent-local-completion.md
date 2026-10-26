# 本地意图识别与任务路由补齐验收

依据设计第02～06、08节，补齐当前个人助理有条件交付的本地链路。这里的完成范围不包括整个A/B/C、跨员工运行时或正式业务版本发布。

## 交付内容

- 新增独立 IntentPacket、RoutingContext、RoutePlan 契约及 Java/Python 生成模型；旧无版本路由元数据仍兼容。新计划绑定输入摘要、调用引用、策略、能力修订、deadline、步骤输入输出引用、前置条件和失败策略。SDK 拒绝重复/循环/缺失依赖、非法引用及与上下文不一致的计划。
- L1 只识别目标与槽位来源；L2 根据固定本地策略产生计划；统一执行器实际逐步执行，复验环境、策略/能力版本、输入绑定和原授权，保留原任务服务的签名、owner、幂等、取消账本、fence 和持久化回执。支持本地独立部分执行和 require_all 前置检查。
- 受控任务写入后不再调用回答模型，直接读取持久化 receipt；未知/取消请求状态不改成成功，不自动重试。任务多候选只显示真实任务ID和状态并要求选择，不把内部任务JSON输出到聊天正文。
- 增加 off/shadow/read_only 慢分类模块，限制一次调用、独立超时、私有结构化输出、只读目标与槽位来源；不向模型传票据或授予工具。通过正常 Keel API 管理 classify staging 提示词v2，真实结构化探测成功。这是前轮状态；后续已完成独立质量门槛并在staging启用read_only，见[专项验收](2026-10-26-intent-classifier-quality.md)。
- 增加入口、计划、步骤开始/终态的受控 Trace/审计记录，13项白名单包含 plan_id、step_id、request_ref、capability_revision、step_status；实际调用另带 step_id。管理员只可为本助理staging增加固定字段，预算/权限/地址仍受原边界约束。审计策略变更通过摘要注解触发接收端滚动更新。
- 补 GET candidate，返回与原 PUT 一致的最新草稿基线，避免正式详情快照的hash无法用于编辑草稿。真实API已完成审计字段/分类提示词声明对齐，过期hash、敏感字段、越界预算及其他环境均被拒绝。

## 代码、制品与测试

平台代码已合并 [PR35](https://github.com/dezhiguan/keel/pull/35)、[PR39](https://github.com/dezhiguan/keel/pull/39)，相关服务最终源为29e421a3f24d（包含上述路由提交）。助手实现及修复分别在 [PR13](https://github.com/dezhiguan/keel-assistant/pull/13)、[PR15](https://github.com/dezhiguan/keel-assistant/pull/15)、[PR16](https://github.com/dezhiguan/keel-assistant/pull/16)、[PR17](https://github.com/dezhiguan/keel-assistant/pull/17)、[PR18](https://github.com/dezhiguan/keel-assistant/pull/18)。最终候选应用0.5.4，SDK锁定官方1.0.22，策略 assistant-staging-2。

官方SDK wheel SHA256：`8561e0e294e36914e74a6e596f20a30187efd05a44ccfc30959cf108ae46d50c`。SDK发布、Java starter发布均成功，来源为 [官方Release](https://github.com/dezhiguan/keel/releases/tag/sdk-v1.0.22)。助手只使用这个发布制品，不引用平台工作区源码。

| 验证 | 结果 |
| --- | --- |
| 助手完整回归 | 332 passed，普通任务中18项PG测试跳过；这18项在独立真实PG CI运行，不算阻塞或伪造通过 |
| 任务PG/Worker/策略 | [独立CI](https://github.com/dezhiguan/keel-assistant/actions/runs/40222968876) 82 passed；与普通回归交叉，不把数量相加作为独立用例数 |
| SDK完整回归 | 372 passed；新契约/结构化分类专项72项通过；Java/Python消费相同正反例 |
| 平台CI | Java、生成模型一致性、Python SDK、devflow通过；候选读取与管理员白名单专项通过 |
| SSE协议 | 3项通过，正式Docker构建通过 |
| 制品上传 | GitHub artifact配额不足；保留失败记录，使用已核对基础镜像、完整git archive及官方wheel构建不可变OCI镜像 |
| 实际部署 | 双副本源码/配置哈希、版本及SDK核对；server/audit双副本核对保护类、镜像与JAR摘要 |
| 真实业务 | USER请求固定自身会话与 acceptance:intent-20261026；包括真实检索、员工查询、任务提交/报告、重放、补材料、运行中取消与取消后拒绝重试 |
| 真实页面 | 浏览器调用树可点击计划/步骤，输出含plan/step/request/policy/能力修订及终态，无页面错误；步骤节点是状态事件，实际耗时见工具和generation |
| 审计 | 双接收端伪造token=401；合法事件及白名单过滤通过。四组真实计划/步骤事件读回与返回计划一致 |

本轮公开JSONL为17条路由题，不是35项完整评测，更不是原业务100条门禁。对所有矩阵条目逐项标记见[设计第08节](../design/Keel-意图识别与任务路由方案-20261025.html#s8)。20项当前A的正常/边界场景有真实证据；其余分别标注自动故障回归、本地子范围通过或B/C/真实环境前提阻塞，不写“35/35”。

## 真实发现与修复

1. 全量回归发现计划deadline的Python日期对象未转JSON字符串，已在计划边界规范化并复测。新增模型必须先过实际发布SDK校验。
2. 安全复查发现否定/引用员工列表可能被当成实时查询、分类器可把长ID子串当目标，已收紧并补回归。
3. 首轮USER中“全部完成”干扰末步槽位/子请求条件，虽然仍零动作，但返回了错误意图或多余澄清。已修复整体限定语处理，真实复测执行前拒绝、两步成功和中途失败后的已执行结果。
4. 真实慢分类v1返回retrieve_state/list且携带指定员工ID，被校验拒绝，零业务工具、零用户分类token。修订合法意图/动作及list/detail槽位关系为受管v2后，真实探测返回detail并通过。单条兼容探测不是独立质量门禁，正式流量不启用。
5. 任务列表原来将内部JSON直接输出到正文；已改为真实ID/状态列表，保留多候选澄清，增加内部字段不进入正文的回归。
6. 首轮任务观察窗口太短，报告仍running；后续回查已succeeded且持久化验收passed=true。首次取消夹具尚在queued，不能当成运行中取消；重新等待观察到running后取消、拒绝重试、attempt仍为1的真实复测通过。原记录均保留。
7. 测试登录凭据正常过期时服务返回AUTH_TOKEN_EXPIRED；按用户既有授权刷新后复测。最初配置请求漏控制台写请求头被403拦截，补齐既有协议后通过，未绕过写保护。

8. 无效任务引用可能被旧正则截成合法32位ID前缀，已增加词边界，四种后缀/前缀的取消回归确认零工具调用；实际负向验证保留证据。

## 仍未启用或未达到的门槛

- 远端能力目录、跨员工持久化受理、远端幂等/查询/取消、并行原子预算预留和复杂重规划仍属B/C。当前策略拒绝remote/approval，不以schema枚举或本地步骤代替远端实现。
- 慢分类原未完成项已在[后续专项](2026-10-26-intent-classifier-quality.md)补齐：独立质量、延迟及费用达标，staging read_only启用。正式prod未发布；云端调用树原Langfuse额度403阻塞已于2026-10-27补验解除，真实16节点及页面点击通过。
- 路由审计接收端仍为内存实现，低风险spool为容器本地；认证、白名单、Trace及HTTP204不等于DB/MQ持久化和跨重启不丢失。高风险失败关闭有既有SDK回归，当前没有获准高风险业务用于共享线上注入。
- 真实第二USER、共享撤权、断流/租约故障注入未具备安全独立的测试前提；PG/故障夹具覆盖不冒充线上全通过。
- 正式业务100条门禁未降低，原95通过/3失败/1待复核/1双USER阻塞记录保留。当前是staging运行候选，不新增正式版本或挪production标签。
- 草稿清单历史预算为5，实际应用与网关预算为110。此次候选对齐只修改审计/提示词声明，保留原预算边界；正式发布前须通过预算授权流程处理历史漂移，不借本次审计接口改预算。
- 首轮全局CD因meta-agent旧bootstrap Key缺失以及一次控制台SCP中断失败，原始失败保留。后续主干[PR40](https://github.com/dezhiguan/keel/pull/40)处理了该部署故障，[最新全量部署](https://github.com/dezhiguan/keel/actions/runs/40221196129)已成功；最终server/audit为29e421a3f24d，包含本轮路由保护，双副本JAR/镜像复核通过。

详细输入、初始失败、复测、真实任务/Trace/账单、候选API、页面截图和制品摘要存于独立助手的 `docs/evidence/intent-local-20261026`。凭据不进入仓库、命令行或证据。

本轮统计：90次真实USER聊天流、87个不同requestId；9次过期认证保护性拒绝单列；四轮服务冒烟共32次通过。已关联49条真实模型尝试账单，按requestId+attemptId去重，合计¥0.126583，无待确认费用，低于5元测试上限。首个探测的非有效Trace通过同alias及记录时间窗唯一账单关联，未冒充语义Trace。三个自建会话已通过正常API删除，任务与初始失败保留。
