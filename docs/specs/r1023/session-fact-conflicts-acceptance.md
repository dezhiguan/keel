# 会话事实冲突与记忆证据验收（2026-10-23）

## 交付结果

会话内增加独立事实账本：实体、属性、时间条件、原文消息来源，以及active/pending/superseded/retracted状态。普通相反陈述保留双方pending；用户明确更正或复述具体值确认后才建立replaces/resolvedBy替代关系。撤销保留历史，不删除原文；不同年份/条件分别处理。只采集用户原文，不把助手回复或摘要当事实来源。

提取结果由代码校验来源、逐字证据、明确意图和真实目标。当前输入只参与本轮派生视图，平台保存原文后下一轮重放持久化，避免中断输入成为已提交事实。每条历史消息必须产出操作或显式忽略，漏处理不推进游标；格式/证据错误在同一35秒期限内最多重提一次，不回传错误候选。HTTP预算、限流及网络错误不重试。最终失败保留原文和游标，后续补处理。

PG和SQLite使用版本CAS。失败者读取权威版本；已覆盖同批原文时只校验重放当前操作，不能覆盖胜者。有界批次及超长未处理原文明确incomplete/gaps，账本是派生索引，不替代原文，也不保证语义零漏检。原有摘要、会话搜索和引用回查继续使用。

## 代码、发布与部署

- 底座：核心PR24/3fa04da；共享期限PR25/365f34c；静态保护/范围归一/权威版本重放PR26/688fcd0；真实对象metadata兼容PR27/a7e5146；维护调用配置PR28/b05c2b8；明确意图/来源覆盖PR30/d1960e1；当前问句兼容ababda4；有界格式重分析8d1b607。
- 最终官方SDK **1.0.17**，源码8d1b60770fb3d5e4e1b69956603f18a895995131，wheel SHA256 `04e15c7fedaefb229386891293d7e2b411261c520fba4b83ba49e80a484746dd`。
- 助理源码e7a5205dfe4dd5949b7eeec014553e056461bc4c。镜像`context-8d1b60770fb3`，manifest `sha256:563ff4c29c70a7363fef5c8a468a2760ba5a1b5142e741225beeb24ffcf8d3b8`；两个副本均Ready，SDK版本/五个关键模块的官方wheel哈希/governed模式/10,000上限逐个核对通过。
- 通过既有单节点containerd导入不可变增量镜像，保留回退镜像；没有将底座源码复制进助理。平台服务保留并行更新后的部署，实际镜像见证据JSON，未用早期版本覆盖。
- SDK完整CI [39930604254](https://github.com/dezhiguan/keel/actions/runs/39930604254)/[39930612361](https://github.com/dezhiguan/keel/actions/runs/39930612361)、正式发布[39930612370](https://github.com/dezhiguan/keel/actions/runs/39930612370)、助理CI [39931062777](https://github.com/dezhiguan/keel-assistant/actions/runs/39931062777)均成功。本地SDK会话/模型专项67通过；助理259通过/18跳过、流式协议3通过。服务端来源/越权/过期/CAS及网关配置测试在对应完整CI通过。

业务仍为0.3.7、受管理answer v13，正式100条旧门禁结论95通过/3失败/1待复核/1真实双用户阻塞保持。本专项不冒充正式业务发布或重跑100条通过。

## 真实部署验收

| 检查 | 实际结果与链路 |
|---|---|
| 普通矛盾待确认 | PG及模型输入保留两个pending，未取最新值：[c79a2118](https://keel.ragforge.net/traces/c79a211814573a5badde7bc9d48cedb6) |
| 明确确认与替代链 | PG版本4，新值active、旧值superseded，replaces/resolvedBy对应；904/906/910分别为旧说法/新说法/确认：[85d9ead1](https://keel.ragforge.net/traces/85d9ead15104b87a305e5818419ace21) |
| 已失败会话补处理 | 无需重发原话；确认894已补落库，PG版本3、游标898，新旧关系正确：[9ecdb62c](https://keel.ragforge.net/traces/9ecdb62cd34abd47fc1b01c9d81ff1c0) |
| 年份隔离与撤销 | 今年retracted，明年active，均保留原文来源：[3b6655f4](https://keel.ragforge.net/traces/3b6655f42eb36967078a878bed8cd14e) |
| 跨副本竞争 | 两个不同Pod同时读取版本0，最终仅版本1、两个pending，未丢失或覆盖：[34bf8d9f](https://keel.ragforge.net/traces/34bf8d9ffc67918b8b22e6be6c8e28aa)、[155e2144](https://keel.ragforge.net/traces/155e2144d349153c458a663d18d3f894) |
| 摘要引用回查 | 摘要版本1；搜索命中0，reference.read实际读取926；完整原文6532字符，SHA256与保存原文一致；context.used=true，实际llm.chat同时含权威摘要与回查片段：[09f4ec97](https://keel.ragforge.net/traces/09f4ec97686fa99b3b39015af3838b1e) |
| 原硬上限 | 12,000字符当前输入，0.62秒返回AGENT_CONTEXT_EXCEEDED，无模型generation、无网关账单：[2006512b](https://keel.ragforge.net/traces/2006512b28c140cbcc8419224d1f924c) |

## 链路中怎样核对

切换调用树，选择memory.summary、memory.reference.read、memory.facts或memory.context，在“记忆处理证据”查看版本、原文消息编号、内容哈希、状态和采用标记。检索命中不等于已采用；还需核对后续llm.chat输入。

摘要样本中reference.read节点`7f4019d93dfa4f89`读取926，offset=0、totalChars=6532，完整原文SHA256为`41b4507cf889216eec2e13a792cbb45c4b00b8adb586a7af6391db24363364a8`。context节点`e863f7a08eb92bb6`为版本1、used=true、组装7569/10000 token。最终generation `8b1397521094a1f9`实际包含该版本摘要及原文片段；片段之外仍完整保存，不宣称已阅读全文。该6532字符消息超过单条事实提取6000字符预算，因此facts节点明确记录gaps=1/incomplete；这是派生索引的覆盖状态，完整原文、摘要及引用回查均已另行核对。

所查普通记忆span均无用户原文input/output，原文只在generation材料核对。真实Langfuse对象metadata已经生产详情组装逻辑验证，部署静态资源包含证据面板；测试登录凭据已失效，未做已登录浏览器点击复测。[机器可读证据](session-fact-conflicts-evidence.json)保留状态、追踪节点、采用检查、部署及账单摘要，不提交原始generation正文或凭据。

## 保留条件与费用

10,000 token输入硬上限、staging日预算110元保留，应用凭据哈希未变；dev/test网关Key仍无。事实提取与摘要是实际受治理模型调用，同Key计费。最终一轮29笔均SETTLED，合计0.067964元；包括前期失败和预验证的已关联样本111笔（110 SETTLED/1 FAILED），已结算0.315359元。未清理既有预留或改写账单。

15个独立测试会话的PG消息/摘要/事实/会话行及两个Pod相关SQLite记录已清理并查询确认0残留；链路、审计和账单保留。原用户会话52条原文逐条SHA256一致。

## 未计为通过的早期样本

SDK12曾15秒提取超时，SDK13确认持久化和摘要耗尽期限；SDK14遇到普通陈述误标更正、虚构目标编号和空提取抢先推进游标；SDK15过严拒绝ignored current；SDK16产生非法ignore操作和重复deny。均保留失败追踪，未把正确文字回复当作持久化成功。SDK17以新会话、原失败会话、真实PG状态和实际模型输入重新验证。

另有验收SSH连接超时样本f038f6e02be413d358598650e11b46e7；链路表明模型及事实节点已经完成。验收客户端关闭测试复用连接并按final/error终止事件读取后继续，未将该连接超时算成功。本机早期完整SDK套件曾317通过/3个CLI或spawn启动超时，最终以同源码完整CI成功及上述真实验收为准。
