# 意图路由 Phase A 候选部署验收

设计 v0.2 对应实现提交在 [平台 PR 35](https://github.com/dezhiguan/keel/pull/35) 与 [助理 PR 13](https://github.com/dezhiguan/keel-assistant/pull/13)。这是 staging 候选验证，B/C、慢分类和远端保持关闭，未宣称完成全 35 项或正式发布。

SDK 1.0.20 已通过 [完整 CI](https://github.com/dezhiguan/keel/actions/runs/40173715580) 并 [正式发布 wheel](https://github.com/dezhiguan/keel/actions/runs/40173791538)：Python SDK 356 项、生成契约 47 项、devflow 9 项，Java 全模块检查成功。助理修复版完整 CI 为 [40174277300](https://github.com/dezhiguan/keel-assistant/actions/runs/40174277300)：297 项通过、3 项 SSE 协议通过、完整 Docker 构建成功；[真实 PG 任务 CI](https://github.com/dezhiguan/keel-assistant/actions/runs/40174279872) 82 项通过（含普通任务跳过的 18 项 PG 用例，不能重复相加）。

助理运行代码 31abb09be42ed5b3efe5cb3c231f1e8b76105f42，镜像 intent-31abb09be42e，0.4.0 / SDK1.0.20，双副本 Ready，65 个源文件哈希一致。8 项真实服务冒烟最终全部通过；首轮错误响应缺路由证据的问题已修 SDK 并复测。模型两轮账单共 10 条，合计 ¥0.019944，无待确认费用。详细服务输入、trace、运行哈希、构建来源与账单证据位于独立助理 docs/evidence/intent-routing-20261025；不包含登录凭据。

审计接收端镜像为 keel-audit:aaa75f1c1422，[构建测试 CI](https://github.com/dezhiguan/keel/actions/runs/40173712689) 成功。策略只覆盖 keel-assistant/staging，白名单由运行清单同步，token 只保存 SHA256。两个副本各自通过伪造 token=401、合法事件=204、单条事件、严格字段过滤，[最终证据](evidence/2026-10-25-intent-routing/audit-final.json)。八个实际冒烟请求均读回计划和终态，字段完整受限，权限拒绝正确记 denied，[实际路由审计](evidence/2026-10-25-intent-routing/audit-route-final.json)。

首轮审计测试失败保留在 [initial](evidence/2026-10-25-intent-routing/audit-live-initial.json)：另一条 main CD 将接收端覆盖为旧版05219b34f009。重新部署正确候选后通过，验证工具新增预期镜像检查。今后的 main 发布须包含本 PR 的镜像代码与 Secret 引用，避免再次覆盖。接收端目前是内存存储，不能据此声称完成数据库持久化/跨重启审计。

合并 main 后，重复 CI 的一次 Java 任务发现既有 RoutePolicy 内存 HashMap 的并发异常：[40173950940](https://github.com/dezhiguan/keel/actions/runs/40173950940)，调用栈 RoutePolicy.observe:122，GatewayTest 五十并发预算测试触发；另一轮偶然成功不能抹掉此失败。368fd10 改为并发 map、按 channel 锁定断路器变更、保证 openUntil 可见性，并增加 500 次并发观测回归。本地 keel-llm 全模块测试通过；本机 Docker 不可用，数据库项以完整 CI 结果为准。

修复后的两轮完整平台 CI [40174510001](https://github.com/dezhiguan/keel/actions/runs/40174510001)、[40174512809](https://github.com/dezhiguan/keel/actions/runs/40174512809) 均成功，候选 [CD 检查](https://github.com/dezhiguan/keel/actions/runs/40174512772) 成功（功能分支不自动部署共享服务）。本轮实际部署范围为助理及审计接收端；RoutePolicy 修复已经提交验证，尚未另行替换共享模型网关镜像。

控制台沿用既有 TraceDetail 的 outputSummary 展示 intent.route 和 intent.route.result，不新增字段或改布局。已读真实 Langfuse 路由节点及 generation/usage/requestId；浏览器点击尚未验收。现有测试登录 /me=401、refresh=503，无法取得合法 USER 的任务/历史上下文；共享浏览器占用，未干扰其他任务。需恢复有效登录后继续全部适用 A 期授权业务、多轮、任务生命周期和页面验收。完整矩阵状态见独立助理 docs/intent-routing-acceptance-20261025.md。

未绕过候选清单兼容性检查、受保护提示词标签或既有评测发布门禁。新增 audit.captureFields 的正式候选清单对齐及正式评测仍待完成；当前 PR 保持候选状态。
