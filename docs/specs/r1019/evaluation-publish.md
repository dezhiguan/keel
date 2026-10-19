# R1019 完整评测后发布入口

页面门禁通过只显示「可以发布」，没有可执行入口；智能体详情的 prod 按钮仍是未接入提示。人工编写的代码智能体无法从页面完成评测到发布。

增加从完整评测发布的入口：只接受服务端持久化的 DONE、passed、complete=true 的评测，镜像由评测记录读取，不接受浏览器自报通过或镜像。人工编写智能体可操作，meta-agent生产的研发员工仍只能可信CI发布。

SDK final.meta 可选附带实际加载的提示词版本与fallback状态；评测保留此来源并要求同一提示词的版本一致，fallback不能作为发布证据。生产发布前对照当前staging标签，任何版本变化拒绝发布。全部有效用例逐条完整执行的结果才允许发布。旧的漏算结果没有complete标记，不能使用。

新POST /eval/{agent}/runs/{runId}/publish，body env=staging|prod。服务端复用原门禁回写与ReleaseService：staging创建绑定实测镜像和runId的代码版本快照；prod只移动已验证的同一staging提示词。先验全部条件再更新。页面展示确认的环境/运行号，进行中锁定按钮。

新增字段全部可选；final.meta仍是Java/Python已有Map，无破坏性DTO变化。评测结果增加complete和promptVersions，run状态增加agent/image供核对。审计只记版本和hash，不含提示词正文。页面旧prod占位按钮改为导航到评测发布入口。

验证：旧部分结果/失败结果/版本冲突/生成员工不可发布；完整结果读取服务端镜像而非客户端输入；部署后四个智能体经页面发布并核对版本、提示词标签、最终调试。

重复运行同一staging版本的门禁也更新最新promotion状态和gateRunId，不能保留上次PASSED绕过本次失败；生产发布必须匹配同一个门禁运行号。版本快照同时绑定staging/prod的evalRunId，避免并发版本导致记录错配。

原 /agents/{name}/releases 也必须校验：evalRunId 指向完整通过结果，CLI旧路径的gateRunId必须对应当前staging提示词的PASSED记录；不能通过旧接口绕过新入口。所有校验须在移动production标签之前完成。

生产环境复测与发布的快照必须保留评测源环境，不能硬编码dev，否则会把prod对象切回dev。发布SQL按eval_run.env选取和创建不可变版本。
