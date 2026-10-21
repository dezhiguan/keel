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
