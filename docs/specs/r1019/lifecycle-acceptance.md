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

## 评测结果证据作废

qa-tools 首轮52条中有5条超时/断流被SDK丢弃，页面47条仍通过；qa-guard51条中1条超时，页面50条1.00仍通过。上述旧结果不能用作完整门禁通过证据，后续必须逐个完整重跑。见 eval-completeness.md。

## 1dd3ca2 部署后专项复测

- CD 39239761584 成功；4把QA Key的qwen3.8-flash专属非思考配置已合并至既有网关配置，其他模型/智能体参数保留，网关已随CD滚动。
- RPM=1首请求成功，后续真实请求返回LLM_RATE_LIMITED，trace 7e056ad6e09f12429466b30b0202cc73；TPM=1按真实结算用量拦截下一请求，trace 91162a4c9a114415ad2941a09667aa9b。TPM不承诺首请求严格token硬上限。证据 quota-retest.json。
- 并发=1，两个独立调试页面同时调用，第二页被LLM_RATE_LIMITED拒绝，trace 02784d20101ce31306d75c8ebc1f8f24；第一页收到287条事件后点停止，显示已取消。证据 quota-concurrency-stop.json、sse-stop.png。配额均恢复不限制，日预算仍30。
- 结构化历史：先查询故障，再查询咨询，第二轮正确调用shift.policy(category=咨询)，trace 74c1ca9075e112902234e1e3a4aaece9，6.16秒完成。证据 structured-history-consultation.json/png。
- JDBC连接错误再次出现且同一时间日志有MVC异步30秒超时/错误体转换失败；见debug-stream-timeout.md，数据库自身关闭原因未确认，不能标为已修复。

## 最终环境切换计划

完成dev完整门禁与页面发布后，分别通过页面下线dev，按代码智能体必要的登记/部署阶段以正式清单重新登记prod，保留数据集与审计，重新自检、完整评测及调试。部署模板按每个profile.env标注环境，其他5个1017智能体保持dev。QA模型参数仅为自己4个dev/prod别名设置，不调整其他生产Key或全局路由权重。

## 部署连续性及护栏链路复测

- `2d22deb` 的 CD 被 `123cefb` 前端提交取消，下一轮只比较相邻提交而遗漏后端/SDK。`7cc041c` 增加完整部署快照基线、首次全量和累计差异回归检查。随后 `04f9ddb` 再次取消前轮，其新 CD `39246994008` 确实重新包含全部服务，未遗漏待部署内容；保留并快进合入其他提交。
- 链路页人为中断一次浏览器请求后，显示持续错误和重试按钮，点击后恢复真实链路 `16db5fa2474cf4d0eb5313a4f4414caa`。证据 trace-error-retry*.png/json。节点 SETTLED 文字不在默认选中节点，验证以完整 DOM 和真实接口数据为准。
- 护栏真实拦截 trace `b987c2b703e14f13b3f5540e2ac91568`，重试后可查看 guard.input 与 agent.run 两个节点，无模型调用、总费用 ¥0.0000。护栏页显示用户输入命中2个样本及该关联链路；样本范围说明可见。首次读取失败后点击重试成功，不能把外部读取失败当成首次即通过。证据 guard-zero-cost.json/png、guard-hit-count-after.png。

## 完整部署后的评测阻断与提示词迭代

- `04f9ddb` CD `39246994008` 全部成功并生成完整部署快照 artifact。后续提交将对照这个已验证版本累计部署。
- 工具智能体真实完整回归 `ev_0708419691d746f2a9f112676347cc04` 被完整性门禁拒绝：日志3条用例达到6步上限，结果 FAILED / IncompleteExperiment。Cloud实际 root span `0420c41e6005516db88bd4516c67a187` 对应平台知识问答，模型错误地持续使用仅查工具能力的目录搜索。不能删除这些用例或调低门禁来掩盖问题。
- 通过页面编辑新提示词v2，明确目录不是知识检索、至多补充一次目录查询、证据不足应说明局限后结束；工具智能体已在dev/test/staging生效v2。保持原问题、原门禁，需重新完整评测。
- 限流账单复测：RPM1首请求成功，第二请求拒绝；页面真实最近拒绝记录显示 qa-routing-1019-dev、网关配额拒绝、模型未调用、¥0.00。限额恢复为空、日预算30。证据 quota-ledger-retest.json、quota-ledger-page.txt/png。

- 提示词v2均经页面在dev/test/staging生效。工具完整回归ev_5636f1f3e8684e419ccc0d370213a5ec仍因1条无效shift.policy类别失败；SDK仅广告目录工具却执行其他manifest工具，见bounded-tool-refinement.md。继续修复实际调用范围和补查次数，不能标成门禁通过。
- 记忆重启与双副本：先记住普通测试代号「白鹭重启1019」，trace aa77fec8689711eba6e858c417e2b4f0；滚动重启并确认2个新副本就绪后刷新，准确回忆，trace 516a7e0bececc7adafda189a4a036d61，再次回忆7159ae76c2b96dab98500f6217dc8e53。新会话明确不知道另一会话代号，trace73922989762fb3eec2f9b87de5cb867e。已恢复1副本。此证据说明重启后PG历史可恢复、2副本运行时使用正常；尚未凭instance_id证明两次回忆分别命中不同副本。证据memory-restart-replicas.json、memory-restart-isolation.png。

## 0e691f1 完整部署与首个完整发布

- CD39252812770成功。评测初始化真实列表请求延迟4秒：修复前按钮仍可用，修复后禁用，数据返回后恢复。证据eval-init-before/after.json/png。
- 工具完整运行ev_69f9cf29d13a4e23b3d8fcc20d77cdfb：52条全部完成、complete=true、promptVersions.answer=2、scoreTotal=0.96875、门禁0.95且passed=true。页面分别发布staging/prod成功，版本v1、镜像qa-mainflow:0e691f1f0af8、门禁运行号一致。注意此时运行环境仍dev，正式环境登记部署与最终复验未完成。
- 该轮仅1条评分失败，trace dd390ddace20e756e1f878380c505696。页面真实答复明确「不能」在无证据时声称已发布，并说明工具目录并非发布记录；失败是技能用例固定整句expected字面不匹配，未删除用例或改成满分。证据tools-single-score-failure.txt/png。评测页面缺少此用例链路入口，另补可查看的未通过评分表。
- 旧部分通过运行ev_5e8b2ea395ae4a18a224e55ffccc656e的发布请求实际返回400，提示只允许完整通过且带提示词/镜像证据。证据old-partial-publish-rejected.json。
- SDK必要参数实测：同一QA Key经真实网关以tool_choice=none请求，200、0工具调用、正常文本、实际qwen3.8-flash、64tokens。证据tool-choice-none-wire.json，不代替页面完整评测。
- 新发现pii:mask实际无运行逻辑，页面仍原样输出虚构手机号，证据pii-before.json/png。先记录再补全SDK，详见pii-output-mask.md。输出范围明确为大陆手机号/身份证，不宣称清理用户输入。

## 四个dev完整门禁与页面下线/正式登记

- memory ev_8020d57d72ff44cf9526b04713c7725a：51条完整、1.00、answer v2，staging/prod均发布v1，镜像0e691f1f0af8。routing ev_24cfb02b18fb4a08a3a4acc8c29b5378：51条完整、0.9625、answer v2，staging/prod均发布v1，同镜像。两轮分别于13:06:57Z和13:02:44Z结束；主流程部署13:06:24Z起按9服务顺序更新，13:09现场仍确认两服务为0e旧镜像，故候选调用未跨自身镜像切换。
- routing两条评分失败均为否定预算声称被误报，真实答复见routing-score-failures.json。保持历史分数；通用评分器补否定并加强转折断言判断，准备下一代码版本重评。
- guard ev_74b7729f5b4442e6bc2db43e63dd4920：52条完整、1.00（含独立pii维度1.00）、answer v2，staging/prod均发布v1，镜像e78987034bf2。不把旧50条1.00残缺结果作为证据。
- e789的CD39256386759首次仅meta-agent scp连接中断退出255，其他组件完成；仅重跑失败任务后全部成功，完整快照已记录e789。未改其他服务的业务设置。
- PII实页after返回138****8000，原始SSE不含完整测试号，包含guard.pii事件。before原始文件在复测时误被覆盖，已保留错误命名的新态为pii-after-first-call，并从修复前持久化会话取回真实旧答复/截图及原trace e9635fe26c800624fd88304784896cb0，before.json明确其来源，不伪造原始SSE。after.json/png为新调用。
- 链路列表先发全部、后发memory筛选，实际窄响应17043ms、宽响应17780ms；两者完成后仍只有memory行。评测页显示52条完整并可点击未通过评分用例直达dd390链路。
- 四个智能体依次通过页面下线，均HTTP200，调试页明确Key吊销且无输入框；随后按完整代码清单登记prod，均HTTP200、协议自检通过。记录retire-register-prod.json及四份retired截图。实例代码/Secret更新与正式环境评测尚待后续部署，不把登记成功当最终调用成功。

## 正式环境准备期间网关配置兼容问题

- 自身QA八个Key默认配置加入DeepSeek thinking对象后，旧RequestDefaults只允许布尔/整数选项，导致新Pod启动校验失败；旧就绪Pod继续服务。
- 已撤回这八个scope中的新增thinking对象，保留其他参数与其他智能体配置；随后检查网关2/2副本就绪。再次执行幂等回退移除0项。
- 补严格对象白名单和隔离/显式参数优先/非法对象回归；须等新网关镜像部署后再加QA专属配置，不以仅更新Secret视为支持成功。
- 本次CI还发现旧生命周期模拟厂商仍要求点号工具原名，改为返回请求中的外部别名，并检查SDK调用原名和历史回传；保留端到端工具执行断言。

## d136fac 正式部署与新回归

- CI及CD39264166356第二次执行均成功，首次meta-agent scp255中断，单独重跑失败任务后完整快照成功。网关新镜像2/2就绪后恢复自身8个模型scope并成功滚动，不再出现配置启动错误。
- 四个正式环境页面重新自检全部通过。工具调试trace403da92280b03b9ccc1570fbca3e3bff，tool2/step2/token212/final1，answer v2、无prompt fallback，真实KB引用与技能三段结构正常。
- routing正式完整51条ev_be42954c9e2649db9271c71ac0e39a04，1.00、complete/passed=true、env=prod、d136镜像；页面staging/prod发布均200。页面路由试算三种模型顺序正常，发布策略v2；真实最近43次自身routing-prod已结算账单Qwen12/DeepSeek31，费用合计¥0.120654，近似30/70样本而非长期统计证明。
- tools正式52条运行ev_1f96660302ce4eaf9c134153d5069307因1次最终阶段模型继续工具调用被完整性拒绝。链路c495dd5e784bdb15baba19036dc7970c表明先DeepSeek补查、再Qwen返回工具调用，范围校验拒绝且未继续执行。见final-tool-phase-and-eval-error.md；不发布该失败结果。
- 同时记录失败后页面历史“门禁通过”误导展示截图prod-eval-failed-old-pass.png，补持续错误及刷新恢复。SDK相关54项测试通过；最终正式全量需等待下一镜像稳定后重跑四个，现有d136记录保留。

## ec8a5fe 部署期间补充验证

- 正式记忆智能体SSE385条后停止，刷新PG会话0条、未保留不完整问答。通过链路列表找到实际SDK trace53705ec4c50081a5df12a13db90f640d（浏览器traceparent c44c...是另一层，不冒充SDK根链路）；agent.run和llm.chat均failed，输出“调用已取消”。DeepSeek该次usage未回传，PENDING/费用null符合预留计费规则，非免费调用。证据prod-cancel*.json/png。
- 新控制台与服务端部署后，从头刷新tools评测页，恢复最新失败ev_1f96660302ce4eaf9c134153d5069307，持续错误说明存在，旧“门禁通过”与发布按钮均不再出现。证据prod-eval-failure-refresh-fixed.json/png。最终智能体SDK镜像仍在构建，未提前运行混合版本评测。

## ec8 完整正式结果与回归基线复核

- tools ev_2eb496bc252c4a539c43a96d7408608c：52条、0.96875、完整通过；新链路a33599ecfe1d4400294e93f9dfa30de1复核确实安全拒绝无证据发布，固定整句评分失败保留。guard ev_9731d355dbbd466b830034c9594bf9e5：52条、1.00、完整通过。
- memory ev_68c3bc108d1e42448e35557df7f48437：51条、0.984375、完整通过；e32bd6b3d2bbfdd8cd5ac412e92d5d8b实际只答“依据已提供的目录查询结果，可直接给出可用工具”，没有给出要求的具体名称。这是真实质量失败，不能写成评分器误报。
- routing ev_119e296a07534fec9a4eb21249c46ce6：51条、1.00、完整通过，prodVersion=production。四轮共206条均完成，原始分数、失败和发布记录保留。
- 检查2pt回归来源发现门禁代码读取最近prod/DONE而非实际release绑定证据，遗漏从dev评测后发布prod的基线，且未发布评分可污染下一轮。故不能以四个总分通过冒充门禁逻辑全部正确。补published-eval-baseline.md，修复后仍需部署与记忆完整复验；记忆已存在的质量失败及当时发布保留记录，不抹除。
- ec8真实最终请求Qwen/DeepSeek均hasTools=false、choice=none、0工具调用且有效正文；正式PII模型输出和SSE均掩码。工具2b655097967317a46c0667666979def4调用树首次临时失败、点击页面重试恢复，generation实测Qwen/answer v2/SETTLED/¥0.001703；guard PII链路e98d298552ca7283c4f4f3e3c254c890的generation输出掩码，DeepSeek/SETTLED/¥0.001847。

## 已发布基线部署后的验证与启动504

- 51607b5 CD39274477210成功，服务端332项测试全部通过，其中EvalPersistenceTest五项真实PG测试0跳过。CI单独Java任务因Maven中央仓库下载jacoco插件连接重置失败，已重跑该失败任务，不能称首轮CI全绿。
- memory部署后ev_ce67d496752e4c56a518af32607b24a8：51条、1.00、完整通过；tool prodScore=0.95、catalog prodScore=0.80，正确来自当时已发布的ev68，而非旧dev/prod混用。暂未发布这一新1.00，准备下一轮核验未发布结果不污染基线。
- 下一次启动连续两次约60秒504，active接口200且无新任务；页面持续错误已恢复，未虚构运行号。定位EvalJobs.start/reconcile误用包含外部链路证据的详情查询，至多8个串行trace读取阻塞控制路径。补eval-start-without-observation.md，控制路径只读登记数据，保留资源ACTIVE与实例就绪校验；待部署验证。
- 正式RPM1页面验证首请求成功trace2ad6be18a8e013e5324c1f305f7b4a19，第二次LLM_RATE_LIMITED trace75fa22fd15dc8c5ce75a7478f231bcc9，限制与预算已恢复并回读验证。恢复后的新会话正常返回final、trace4509a3b5fdc8a6ae3ef1ad5a00ec6842。

## b7部署后的门禁与页面性能测量

- 集群直接确认keel-server:b7e5cbfc64d2两个新副本就绪；GitHub状态接口期间反复502且流水线queued状态滞后，CI39277176712已成功，不把状态接口滞后误写成应用未部署。
- 同一memory评测启动修复前两次约60020ms/504，修复后202、浏览器资源计时69.7ms，创建ev_6b7b3384f10b4ba6817db9f9eedabe43。该轮51条、1.00、完整通过；基线仍tool0.95/catalog0.80，未采用未发布ce67的1.00。已通过页面发布staging/prod v2，镜像仍ec8。证据eval-start-after-b7-timing.json及对应运行/发布文件。
- 四个调试入口均可输入，但memory约24秒、routing约37秒。进一步计时routing详情API25095ms、会话API11744ms，确认相同的外部链路补证污染调试与会话读取。新增debug-without-observation.md及可选includeRuntimeEvidence契约；控制入口保留全部原校验，调试页显式跳过观测补证，完整抽屉默认保持完整证据。待部署后复测，不以“能输入”掩盖长耗时。
