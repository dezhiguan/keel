# keel/v1 — 可选异步会话记忆维护

兼容旧 SDK：默认同步，现有 summary/facts 路径保持；新字段均可选。仅个人助理 staging 显式启用。

## 原子登记

final.meta.memoryMaintenance 可选对象：`{mode:"async", summaryThroughId:"非负十进制消息id"}`。非评测、可记忆 final 落盘后，底座在同一事务内合并 facts 任务至新用户消息；summary 只合并到已存在且完整结束的旧轮次上界，不能信任任意跨会话 ID。服务端 `KEEL_MEMORY_ASYNC_AGENTS=agent:env,...` 与 SDK `KEEL_MEMORY_MAINTENANCE_MODE=async` 同时启用；否则走同步兼容路径。可由操作者为 agent_session.memory_maintenance_mode 设置 sync 做单会话回滚或同版本配对验收；null沿用agent/env配置，不新增用户界面。同步会话不投递、不领取异步任务。

## 服务身份 API

路径前缀 `/api/v1/agents/{name}/memories/maintenance`。沿用智能体服务断言，只允许所属 agent、请求 env 对应登记环境及该环境开启的任务；不使用用户短期 token 运行后台任务。

- POST `/claim` `{env,workerId?:"实例标识"}` → `{job:null}` 或 `{job:{id,kind,env,ownerUser,sessionId,targetId,expectedVersion,leaseToken,triggerTraceId,attempt,maxCalls,maxInputTokens,maxOutputTokens,maxCostCny,messages,state}}`。sessionId 为既有 owner:session 哈希；消息为同一作用域内未过期的、连续完整轮次，facts 为既有事实批次规则。目标上界为领取快照，不受后续合并请求扩张。租约到期前提交/续租；上界、版本、原文只由底座提供。
- POST `/complete` `{env,id,leaseToken,state,throughId,calls,inputTokens,outputTokens,costUpperBoundCny,traceId}` → `{saved,more}`。summary state `{version,throughId,content}`；facts state 为现有 ledger。验证后原子写派生状态、推进本批游标并确认任务。CAS/租约冲突返回 saved=false，不能覆盖；调用前按部署配置的输入/输出单价保守上界预留，最大4次、每次领取0.5元上界、每个失败前缀最多3次领取（含崩溃时全额预留），之后停止并报警。上界不是已结算费用；实际费用按generation网关requestId核对，未知费用不得伪造0。价格上界必须覆盖全部可选路由/降级模型，否则禁止启动异步worker。
- POST `/fail` `{env,id,leaseToken,reason,calls,inputTokens,outputTokens,costUpperBoundCny,traceId}` → `{accepted}`；reason 仅枚举类别，不含用户原文，最多3次尝试，耗尽则 failed 并可观察；非瞬态 source_too_large/invalid_output/budget 不无限重试。
- GET `/status?env=...` → `{enabled,counts,oldestPendingSeconds}`；USER 仅能查看自己 session 范围的汇总，SERVICE 仅所属 agent/env。

summary/facts 读取响应可选 `maintenanceMode:"async"`，SDK 据此确认服务端已启用。后台提交不调用不带租约的旧写接口；事务内复用现有校验与审计。任务不存原文，只存来源范围；删除会话同步删除派生状态及任务，不得重建。

后台观测独立 trace，root memory.maintenance 记录触发 trace/task ID；memory.summary、memory.facts 的 status 保持 attempted/ok/failed，并记录 skipped/conflict/timeout/retry 的 reason。不得以后台结束时间延长主请求 span。

兼容说明：旧 summary API 的非控制台 session ID 继续支持已有 CAS；64位哈希控制台会话必须存在并锁定原会话行，提交不得复活删除或过期会话。

平台同事务记录每次领取与完成/失败/租约遗弃的元数据（session_memory_attempt），包含排队/执行时间、目标与实际游标、实例及关联trace、费用保守上界；不存原文，不把崩溃后的未知消耗记成0。该记录用于5分钟负载的收敛P95和进程退出恢复验收。删除会话同时清理该派生任务元数据，独立审计/账单/Trace保留。
