# 单Agent文字分析任务：受控PG队列与可信上下文

## 边界

仅keel-assistant/staging/analysis，执行器永远是同一助理endpoint。没有通用员工委派、外部动作、递归规划或长期记忆。现有agent_run/approval不能替代本任务所需队列。SDK1.0.2的tools.call和ctx.llm足够，平台新增窄任务接口，不改SDK。应用不读PG凭证，由平台现有应用账号管理独立任务表，不能把管理员DSN交给助理。

## 契约与控制台

先新增contracts/assistant-tasks.openapi.yaml，/chat/stream requestId可选，不破坏keel/v1；保留旧客户端兼容，未传时使用绑定用户+session+消息的确定性ID。SDK invoke input新增可选保留字段_keel_task_context/_keel_task_execution，均不是模型参数。控制台沿用现有聊天原型，只有请求ID透传，不建新工作台或任务卡。

task context由真实USER认证线程签发，独立issuer/audience，绑定用户/session/agent/env/text hash/request ID，300秒。eval不签发。服务身份只有私钥断言不能获得用户任务权限。注册清单必须声明keel.tasks.command(WRITE/LOW)及keel.tasks.execution(READ/LOW)，当前用户通过task-scope明确授权后方可用；scope只允许本人组织拥有的本助理staging。只读票据不能验证为任务票据。

command支持create/get/list/cancel/retry。创建材料必须为绑定真实消息的子串，且消息明确要求执行；原目标/输入及hash持久化。稳定请求ID唯一，不同内容冲突，用户隔离按真实subject。任务授权仅保存随机引用、用户/组织、有效期、次数/时间限制；不保存JWT或Cookie。Worker前检查scope/当前账号/授权期限。execution使用绑定task/fence的短期票据，只返回该任务最小资料，标记run/trace关联，不扩大工具或KB权限。

入站去重以绑定当前真实用户的完整原消息摘要为内容依据，不把模型每次提议的材料切片混入入站摘要。相同requestId与相同原消息直接回查首个持久化任务，保留其原规范化材料/目标；模型在重复请求里选择不同切片不改变任务。相同requestId但原消息不同拒绝冲突。并发创建仍以唯一约束收敛到首个任务。

## PG和Worker

新增V33迁移（V32由主工作区独立评测工作占用，部署前须确认迁移顺序与已提交平台基线，不覆盖该工作）。assistant_analysis_task/attempt无外键，独立索引，30天终态保留，unknown不自动清除。task状态queued/running/waiting_authorization/validating/succeeded/failed/cancel_requested/cancelled/unknown；材料缺失时在聊天澄清，不建待补充任务。

Worker使用数据库事务、SKIP LOCKED、30秒租约/递增fence、5秒续租，先落调用意图。只调用本助理当前登记endpoint，HTTP最长120秒；业务代码和SDK模型调用在助理项目。单助理全局最多1个有效运行任务，后台线程有界，不在聊天请求后挂协程。过期执行任务进入unknown，禁止自动重放。人工重试须用户明确接受可能重复费用，最多2次，保留每次原产物。取消执行中调用不承诺模型已停止，返回后保存产物但不标成功。

业务结果由平台确定性检查summary和三条不同风险、source_id=material、quote原文子串及对应建议。失败保留产物与原因，不执行员工/报告中的任何指令。记录task/run/trace、prompt版本、网关费用未知为null；SDK预算估计不当实际费用。

## 验证/交付

独立测试签名/用途/用户/环境/会话/消息绑定，假票据与readonly票据拒绝；PG双领取、fence、超时unknown、取消、重复提交冲突、产物失败与保留、跨进程恢复。原型对齐清单追加R1021-assistant-analysis行。独立平台提交/CI/迁移验收完成后才集成本项目发布；不在共享主工作区暂存或部署其他未提交工作。

## 专项验收隔离

真实USER可在现有聊天请求携带`X-Keel-Analysis-Acceptance`（1–64位字母数字、下划线或连字符），服务端使用`acceptance:<标记>`命名空间并关闭会话记忆，后台分析关联eval标记；身份和授权校验不变。普通eval worker不取得任务上下文，不能代替用户。正常用户任务与专项命名空间互不可见。

## 历次产物回查与取消含义补充

get在同一owner/namespace校验后返回最多两次的attempts记录（序号、状态、原产物、验收、错误、prompt/Trace/run与未知费用），不返回票据、授权引用、租约身份或用户凭证。list仍返回简洁当前快照。失败后重试成功，也可通过同一任务ID回查上次原始失败产物。

取消排队中的重试仅取消后续步骤，不能据此把先前unknown调用当作已停止。cancelled快照在attempt>0时明确已有调用及费用不因取消撤回；只有attempt=0才说明没有发起模型调用。状态迁移仍由原确定性代码控制，不改动执行、预算或其他员工权限。

## 已授权声明的候选接入

现有候选接口把tools视为不可直接变更的权限边界；仅在scope表新增grant不足以登记本版工具。沿用候选接口，不绕过边界或借用管理员DB写清单：真实USER/ADMIN在自己的组织对keel-assistant/staging显式开启task scope、且两项远程工具1.0.0 grant ACTIVE后，候选接口才允许一次加入固定7项声明：assistant.tasks.create/get/list/cancel/retry及keel.tasks.command/execution。其access固定、risk=low、approval=none；旧工具声明逐项完全不变。其他新增工具、共享工具、既有权限修改、环境、身份、endpoint、预算、模型路由及护栏仍经原边界校验拒绝；scope本身不改候选清单。评测期间仍拒绝候选更新。SERVICE、PREVIEW和其他组织不可使用此路径。测试须包括未授权、超出白名单与改变原有声明的拒绝。
