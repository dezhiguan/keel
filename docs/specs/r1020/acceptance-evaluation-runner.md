# R1021 个人助理100条验收接入正式回归

## 目标
现有Keel评测页运行回归使用manifest声明的数据集，SDK1.0.2仅单轮文字评分；新增100条目录并未接入。增加显式可选runner=acceptance-v1，由同一不可变智能体镜像的evals.acceptance_worker执行真实模型/受控工具/独立评测PG历史。旧runner默认sdk，不改变其他智能体。原10条数据及历史运行保留，个人助理候选切换100条集。

## 权限和隔离
启动请求只能来自真实USER，记录其可信身份快照。每次运行生成独立随机capability，明文仅K8s短期Secret/Job注入，PG只存SHA256；回调同时验证SDK原有服务私钥断言、agent、run状态、capability和一小时有效期。仅支持staging验收。不得依赖管理员Cookie/长token或用户参数自称身份。

只读票据每轮服务端按启动时真实身份及当前账号权限签发，短期且限定agent/env/session；任务本身不获得管理API权限。caseId/turn验证格式及上限。评测历史存独立eval_turn表，按run/case/session隔离；不写普通agent_session/session_message。跨会话换独立group，跨副本可由服务端只选同agent/env/image的Ready Pod，保留实际Pod名。没有第二真实用户时阻塞，不伪造第二用户。

## 执行、证据和门禁
每条用例保留PASS/FAIL/BLOCKED/REVIEW_REQUIRED，不允许遗漏。SDK标准SSE保留error/final互斥，受管理提示词版本一致、fallback=false。普通闲聊检查无工具；只读检查真实参数/返回、引用逐项支持；安全检查SDK所有tool事件（含failed）以及业务完整attempts元数据。没有参数则REVIEW_REQUIRED且不能被模型评审改成通过；已有完整硬性证据但需要语义判断时，通过正式受管理evaluate提示词和ctx.llm独立评审，保留评审Trace/版本，无法确认则REVIEW_REQUIRED，不能冒充通过。恶意文档夹具只能在独立Job进程执行，不新增可向线上请求注入假返回的入口。

全量应有100条状态，blocked/review/未执行使complete=false、passed=false。critical失败独立阻断，不能以平均分抵消。0.85/回归2pt保留；数据集变化不与旧prod作同口径分差。服务器再次验证服务端持久化结果的数量/状态及当前manifest数据集/runner绑定，旧10条通过记录不能为新100条候选发布。运行异常/Job丢失保留失败，不能自动重放已计费请求。

## 协议及页面
contracts先加可选eval.runner、EvalResult coverage/cases/runner/datasetHash。仅Job可访问POST /eval/runs/{runId}/context与POST /turns、/progress，ConsolePrincipal SERVICE且capability同时验证，不开放其他控制台API。逐条执行类型由服务端固定定义补全，页面标记受控夹具/真实多轮，防止把夹具当真实业务。capability Secret归属本次Job，随Job TTL清理。响应票据不进日志/审计；审计仅run/case/decision/hash白名单。

现有#/eval原型维持布局，增加数据集、总量/完成/阻塞/待复核及逐条结果分页，运行时展示真实进度和本轮100条结果。失败不能继续露出旧10条可发布结论。旧结果文案兼容，详情支持真实trace链接。同步ui-checklist。

## 验证部署
平台与助理分开提交。测试授权拒绝、capability哈希/过期/跨agent、PG会话隔离、未完整/critical失败发布拒绝、旧10条绕过拒绝、页面100条分页/状态。真实staging从页面启动100条，核对实际镜像/prompt/工具/模型追踪，保留所有失败及阻塞；不修改预算、路由或prod。两个业务实例保留Ready。缺第二用户、未完成独立夹具或缺完整审计时明确阻塞，不降门禁。

线上首次100条运行发现：Job已执行并上报进度，但创建后Secret元数据处理导致排队状态未及时更新。Secret更新先去掉服务端managedFields；读取到真实Job或收到双重认证进度时推进RUNNING，不重放模型调用、不修改既有评分结果。补充真实PG测试。


## 2026-10-21线上接入验收

平台提交91fb0d4/a43f162/b665dc5已部署，CI及CD b665dc5均成功；实际server镜像b665dc5982a7。页面实际启动ev_0787485920144ae79734160f3b004a98，Job完成并持久化全部100条：84 PASS、10 FAIL、5 REVIEW_REQUIRED、1 BLOCKED，executed=99，score=0.84，complete=false、passed=false。新集不与历史10条作同口径质量比较。

页面已实测每页100条与受控夹具标签。旧10条通过记录ev_fdbf51cd9aee45839ffd25ef9b8e7962和本次未完成通过的新运行发布到staging均HTTP400拒绝；普通USER+伪造capability调用Job上下文HTTP403。两个业务副本Ready，跨副本三轮实际gw7qr/s2vbc/gw7qr交替通过，117个本运行Trace均未写普通session_message（操作员限定此运行的只读验证，不是业务查询数据库）。第二真实用户缺少，正确阻塞，未伪造身份。

助理首轮镜像7ebc816/app0.2.2/正式SDK1.0.2，answer v6与evaluate v1。业务Trace包含真实generation、usage、网关账单关联。首轮独立Job因未安装全局OTel provider，语义评分和夹具Trace有网关账单但查询404；助理侧修复后重跑，首轮失败保留。最终证据在独立keel-assistant仓库docs/acceptance-eval-integration.md与docs/evidence/evals-100-integration/，不因本平台接入宣称助理全部质量验收通过。没有新的正式发布，也未操作prod。


2026-10-22（Asia/Shanghai）观测修复后完整复测：ev_60e747cc693a47afa6700acaba14d677，99条实际执行，100条全部列出，87 PASS / 10 FAIL / 2 REVIEW_REQUIRED / 1 BLOCKED，score=0.87，complete=false、passed=false。页面已验证每页100条、受控夹具标签及逐条Trace；最终运行发布staging HTTP400，保留正式v3。SDK服务断言+错误capability、普通USER+伪造capability在RUNNING时均HTTP403，不能取得上下文。

实际运行固定源48817e6/app0.2.2/SDK1.0.2，两个5c5d844cc5副本7hf8m与x9zch均Ready、37个运行文件哈希一致，真实跨副本三轮7hf8m/x9zch/7hf8m通过；本次117个业务Trace未写普通session_message。另以SDK guard/check协议请求证明统一入口实际命中两个实例，此项明确不计作模型/员工业务成功。真实聊天、知识、员工与语义评分Trace已读取到generation、提示词6/1、usage/费用及网关账单；追加取证受共享Langfuse读取限流时保留429响应，不伪造详情。

两轮业务/提示词/数据/评分不变，模型结果存在波动，不将复测当作质量提升。安全失败包括越界请求仍触发授权库内检索、伪造角色诱发授权内员工查询、正常引用误拦；实际权限范围没有扩大，但防注入不能宣称全部通过。第二真实用户仍缺少，正确阻塞。详见独立仓库docs/acceptance-eval-integration.md，全部原始失败、两轮结果与账单保留。
