# 意图路由 Phase A 候选部署验收

设计 v0.2 对应实现提交在 [平台 PR 35](https://github.com/dezhiguan/keel/pull/35) 与 [助理 PR 13](https://github.com/dezhiguan/keel-assistant/pull/13)。这是 staging 候选验证，B/C、慢分类和远端保持关闭，未宣称完成全 35 项或正式发布。

后续真实登录已恢复：使用用户授权的私有本地凭据，登录和 /me 均 200，ADMIN 权限正常；凭据未写入仓库或证据。已补真实 USER 和独立浏览器验收，发现并修复平台取消状态丢失的问题，详见下文。此前的登录阻塞是已解除的历史状态。

SDK 1.0.20 已通过 [完整 CI](https://github.com/dezhiguan/keel/actions/runs/40173715580) 并 [正式发布 wheel](https://github.com/dezhiguan/keel/actions/runs/40173791538)：Python SDK 356 项、生成契约 47 项、devflow 9 项，Java 全模块检查成功。助理修复版完整 CI 为 [40174277300](https://github.com/dezhiguan/keel-assistant/actions/runs/40174277300)：297 项通过、3 项 SSE 协议通过、完整 Docker 构建成功；[真实 PG 任务 CI](https://github.com/dezhiguan/keel-assistant/actions/runs/40174279872) 82 项通过（含普通任务跳过的 18 项 PG 用例，不能重复相加）。

助理运行代码 31abb09be42ed5b3efe5cb3c231f1e8b76105f42，镜像 intent-31abb09be42e，0.4.0 / SDK1.0.20，双副本 Ready，65 个源文件哈希一致。8 项真实服务冒烟最终全部通过；首轮错误响应缺路由证据的问题已修 SDK 并复测。模型两轮账单共 10 条，合计 ¥0.019944，无待确认费用。详细服务输入、trace、运行哈希、构建来源与账单证据位于独立助理 docs/evidence/intent-routing-20261025；不包含登录凭据。

审计接收端镜像为 keel-audit:aaa75f1c1422，[构建测试 CI](https://github.com/dezhiguan/keel/actions/runs/40173712689) 成功。策略只覆盖 keel-assistant/staging，白名单由运行清单同步，token 只保存 SHA256。两个副本各自通过伪造 token=401、合法事件=204、单条事件、严格字段过滤，[最终证据](evidence/2026-10-25-intent-routing/audit-final.json)。八个实际冒烟请求均读回计划和终态，字段完整受限，权限拒绝正确记 denied，[实际路由审计](evidence/2026-10-25-intent-routing/audit-route-final.json)。

首轮审计测试失败保留在 [initial](evidence/2026-10-25-intent-routing/audit-live-initial.json)：另一条 main CD 将接收端覆盖为旧版05219b34f009。重新部署正确候选后通过，验证工具新增预期镜像检查。今后的 main 发布须包含本 PR 的镜像代码与 Secret 引用，避免再次覆盖。接收端目前是内存存储，不能据此声称完成数据库持久化/跨重启审计。

合并 main 后，重复 CI 的一次 Java 任务发现既有 RoutePolicy 内存 HashMap 的并发异常：[40173950940](https://github.com/dezhiguan/keel/actions/runs/40173950940)，调用栈 RoutePolicy.observe:122，GatewayTest 五十并发预算测试触发；另一轮偶然成功不能抹掉此失败。368fd10 改为并发 map、按 channel 锁定断路器变更、保证 openUntil 可见性，并增加 500 次并发观测回归。本地 keel-llm 全模块测试通过；本机 Docker 不可用，数据库项以完整 CI 结果为准。

修复后的两轮完整平台 CI [40174510001](https://github.com/dezhiguan/keel/actions/runs/40174510001)、[40174512809](https://github.com/dezhiguan/keel/actions/runs/40174512809) 均成功，候选 [CD 检查](https://github.com/dezhiguan/keel/actions/runs/40174512772) 成功（功能分支不自动部署共享服务）。本轮实际部署范围为助理及审计接收端；RoutePolicy 修复已经提交验证，尚未另行替换共享模型网关镜像。

控制台沿用既有 TraceDetail 的 outputSummary 展示 intent.route 和 intent.route.result，不新增字段或改布局。已在独立浏览器实际点击调用树与路由节点，登录、两个路由节点、输出摘要和无页面错误均通过；未干扰其他窗口。页面截图及完整矩阵状态见独立助理 docs/intent-routing-acceptance-20261025.md。

## 真实 USER 验收与取消约束修复

共执行 52 次真实用户聊天流请求，包括知识检索及出处、员工查询、继承和纠正、本人历史回查、独立只读+拒绝委派、讨论/引用/条件零任务写入、prepare/supply/create/get/list/cancel、同请求重放及冲突拒绝。后台真实报告含三个有原文依据的风险与建议，持久化验收 passed=true；semantic_review 仍为 pending，没有修改平台审核状态。执行前取消的任务 attempt=0；材料内取消另一任务的引用没有执行。

首轮纠正测试使用了当前不存在的 chat-demo，实际正确返回 SERVER_NOT_FOUND，失败调用参数证明没有沿用旧 ID；原测试错误地要求成功证据，因此保留该检查失败，并以实际存在的 qa-ci-audit-1018 及不存在对象两个分支补测。

真正的实现缺陷发生在运行中取消：Worker 的当前权限复验拒绝后，任务由 cancel_requested 转为 unknown，旧平台丢失取消的重试约束，带费用确认仍可入队，并实际执行第二次。失败任务及全部记录保留，没有改写历史制造成功。fb20e24 在现有事务命令账本查询 cancel，拒绝后续重试，并在 unknown 回执中保留取消说明；未知执行/费用不会被改成已停止或零费用，无需迁移。

[完整 CI](https://github.com/dezhiguan/keel/actions/runs/40179612804)、[重复完整 CI](https://github.com/dezhiguan/keel/actions/runs/40179610273) 及 [实际平台 PG 专项与镜像构建](https://github.com/dezhiguan/keel/actions/runs/40179610305) 均成功。专项配置独立数据库 keel_assistant_platform_task_test，显式启用平时条件跳过的 26 项 Java 持久化测试；新增三项覆盖 Worker 失败、租约过期恢复和 unknown 后取消。此前独立 Python 存储的 82 项测试不能替代这里的 Java 状态机证据。合并 main 的 SDK 专项 42 项也通过。

定向部署 keel-server:fb20e24bd81d，仅替换镜像，完整保留当时 aa107ee 的部署配置与 AskDB 身份支持。两个 Ready 副本的 JAR SHA256 均为 bc8a4b9c36855225aa4b48bdf4fcff52447bae41322af5a0674266628057b389，与 CI 最终镜像内校验值一致；实际字节码含取消检查及 AgentBridgeCredentials，见 [运行校验](evidence/2026-10-25-intent-routing/platform-task-runtime.json) 和 [范围核对](evidence/2026-10-25-intent-routing/platform-task-rollout.json)。

部署后使用保留的真实 unknown/attempt=1 取消任务验证，5/5 通过：带费用确认的 retry 返回 SERVER_INVALID_PARAM，原因明确为已有取消请求；再读仍为 unknown/attempt=1，尝试记录仅一条、没有新增 retry 命令；原报告哈希不变，员工实际只读正常。全部输入/响应见独立助理 user-task-afterfix.json。原始失败、后续修复、成功复测分别保留，不能将这 52 次请求全部计为成功。

这 5 条请求的真实路由审计均读回成功，计划/结果、拒绝决策、plan_id 与响应对应，payload 只含允许字段，见 [修复后审计](evidence/2026-10-25-intent-routing/user-audit-afterfix.json)。USER 与其关联 Worker 的 22 条账单合计 ¥0.059164，无 PENDING；加上此前服务冒烟，共 ¥0.079108，仍低于 5 元专项上限。任务快照的未确认费用字段未改成 0。

另一个 main CD 曾把审计覆盖为 aa107ee；其审计部署完成后已定向恢复 aaa75f1c1422 和策略引用。共享 main CD 的 meta-agent 等其他失败不算本轮候选部署失败，也没有修改那些智能体。本 PR 尚未合并，后续平台部署仍须包含这些代码和 Secret 引用。两个本次新建会话已通过正常 API 清理；原有会话与任务记录未删除。

未绕过候选清单兼容性检查、受保护提示词标签或既有评测发布门禁。新增 audit.captureFields 的正式候选清单对齐及正式评测仍待完成；当前 PR 保持候选状态。

共享线上撤权、强杀 Worker 故障注入及第二个真实 USER 验收仍未执行；相关 PG/自动测试不冒充线上验收。B/C 与正式完整发布门禁保持未完成。
