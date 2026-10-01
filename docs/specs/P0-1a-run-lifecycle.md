# P0-1a 运行生命周期：中断与恢复

## 目标

契约层定义「一次执行可以挂起、之后可以恢复」。冻结后，任何需要人工介入的智能体都通过同一套 `run_id` + `suspend` + `resume` 接入，不再各自实现待办后台、通知、超时和审计。

本任务只冻结契约和表结构，不写实现。实现分散在 P0-6（SDK）、P2-7（网关并发位）、P3-1（审批状态机）。

## 依赖

- 前置任务：无。与 P0-1 同一批冻结，两份 spec 一起评审
- 依赖的契约文件：`contracts/invoke.openapi.yaml`、`contracts/sse-events.schema.json`、`contracts/audit-event.schema.json`、`contracts/error-codes.yaml`（均由 P0-1 创建）
- 依赖的外部组件及其真实行为：
  - auth-gateway：换票 token 600 秒。恢复时必然超过这个时间，SDK 必须重新换票；用户会话也失效的改用 `/oauth/delegation-token`。见 `.cursor/rules/external-apis.mdc`
  - **待确认**：`/oauth/consents` 的授权粒度是按智能体还是按单次流程。决定了「一次授权能不能覆盖后续多次恢复」，影响 `agent_run` 要不要存 consent_id

## 为什么放在底座

留给每个智能体自己实现，会重复四样东西：待办入口（后台页面）、通知渠道（企业微信 / 钉钉 / 站内）、超时与冷却、人工决策的审计。其中审计那一项，`audit-event.schema.json` 的 `decision` 枚举本来就有 `pending / approved / rejected`，`approver` 字段也在——这份 schema 本身已经假定人工决策的账记在底座。

还有两处智能体做不到：

- **换票**：等待超 10 分钟后 token 必然过期，恢复时要重新换票。十个智能体各写一遍就是十次写错的机会。
- **并发位**：网关的并发位按连接持有。智能体在自己进程里阻塞等人批，网关看到的是一个正常长连接，位子一直占着。只有协议层明确「这次执行挂起了」，网关才可能释放。

控制台原型的「人工等待 4m12s」和「待审批 3，最早 18 分钟前」两个数字，也只有底座知道谁在等、等多久才可能存在。

## 边界

**底座管账，智能体管现场。**

| | 归属 | 内容 |
|---|---|---|
| 外部生命周期 | 底座 | `run_id`、状态、挂起原因、谁有权决定、截止时间、决策结果、审计、恢复入口 |
| 内部现场 | 智能体 | 执行到第几步、中间结果、状态怎么序列化、怎么从状态继续 |

分界线写死成一句话：**底座永远不决定下一步做什么，它只负责把现场的引用存下来，在人做完决定之后原样递回去。**

守住这条，Keel 不会滑成工作流引擎。任何让底座「按条件选择下一个节点」「编排多步任务」的需求，都不属于本任务，也不属于 Keel。

## 改哪些文件

```
contracts/invoke.openapi.yaml
contracts/sse-events.schema.json
contracts/audit-event.schema.json
contracts/error-codes.yaml
contracts/tests/**
```

表结构在 P1-3 的 Flyway 脚本里落地，本任务只定义，不建表。

## 接口契约

### invoke.openapi.yaml

在 P0-1 的四个接口之外新增两个：

```
POST /v1/invoke                  # SSE，事件集见下
POST /v1/runs/{run_id}/resume    # SSE，事件集与 invoke 相同
GET  /v1/runs/{run_id}           # 查状态，不返回现场内容
```

`POST /v1/invoke` 请求体加可选字段：

```
idempotency_key: string   # 调用方生成；同一 agent 下重复提交返回原 run
```

`POST /v1/runs/{run_id}/resume` 请求体：

```
{
  resume_token: string,              # suspend 事件里原样带回
  decision: "approve" | "reject",    # reason=approval 时必填
  input:    { text },                # reason=input_required 时必填
  patch:    { }                      # 可选：审批人修改后的工具参数
}
```

`GET /v1/runs/{run_id}` 响应：

```
{ run_id, agent, status, suspend_reason, deadline, trace_id, created_at, updated_at }
```

### sse-events.schema.json

在 P0-1 的五种事件之外新增第六种，并给 `final`、`error` 加 `run_id`：

```
suspend { run_id, reason, ref, prompt, deadline, resume_token, trace_id }
final   { answer, citations[], meta, trace_id, run_id }
error   { code, message, trace_id, retryable, run_id }
```

`suspend.reason` 三个取值，**三个全部写进枚举**，哪怕这一期只实现第一个：

| reason | 含义 | `ref` | `prompt` |
|---|---|---|---|
| `approval` | 等人批准一次工具调用 | approval_id | 给审批人看的摘要 |
| `input_required` | 等用户回答一个问题 | 空 | 要问用户的话 |
| `handoff` | 转人工接管 | 工单 / 会话 id | 交接说明 |

发完 `suspend` 事件后，SSE 流**正常关闭**，不是挂着等。

### agent_run（`keel` 库第 12 张表，P1-3 落地）

```
run_id(pk)、agent_name、env、trace_id、session_id、
status(RUNNING/SUSPENDED/DONE/FAILED/EXPIRED)、
suspend_reason、suspend_ref、checkpoint_ref、
idempotency_key、actor_user、deadline、resumed_count、created_at、updated_at
```

唯一索引 `(agent_name, idempotency_key)`。

### approval_request 的 subject_type（本任务顺带修正）

现有设计里 `approval_request` 只有 `tool_name` 字段，默认审批都是关于工具调用的。但 `audit_export` 表已经有 `approval_id` 外键——**非工具类审批早就存在，表结构却表达不了**。借这次一起改掉：

```
subject_type: tool.call | tool.config | agent.config | agent.retire | data.export
subject_ref:  工具名 / 智能体名 / 导出申请 id
run_id:       可空
```

五个取值一次写全，理由同上（加枚举值是破坏性变更）。`run_id` 非空表示有一次执行正挂起等它，批准后 keel-server 调该智能体的 `/v1/runs/{id}/resume`；为空表示由人直接发起（导出、改配置），没有执行在等，批准后由 keel-server 自己执行。

`approval_policy` 加 `subject_type`，让策略能按类型适配，不要用一套策略硬套五种审批。

### audit-event.schema.json

`action` 枚举增加 `run.suspend`、`run.resume`，理由同下。

### error-codes.yaml

新增：`RUN_NOT_FOUND`、`RUN_NOT_RESUMABLE`、`RUN_EXPIRED`、`RUN_RESUME_DENIED`。四个都是 `retryable: false`。

## 实现要点

- **枚举值现在就要写全**。契约规则是「新增字段必须可选」，但**新增枚举值对消费方是破坏性的**——旧版本 SDK 收到没见过的 `suspend.reason` 不知道怎么办。所以 `reason` 的三个取值、`action` 的两个新值，这次全部写进 schema，实现可以只做 `approval`。漏一个就要升 v2。

- **`suspend` 之后流要关掉，不能挂着**。控制台原型自己写着人工等待 4m12s、待审批最早 18 分钟前。SSE 连接挂十几分钟会占满网关并发位（P2-7 按连接持有），P3-9 的 100 并发 SSE 压测口径会直接失真。网关在转发 `suspend` 事件时释放并发位，`resume` 时重新申请——这是 P2-7 和 P2-8 要改的地方，spec 里先把协议定死。

- **checkpoint 是第三个数据出口**。现场快照天然包含对话历史和中间结果。铁律管住了 span（不放原文）和审计（只收白名单字段）两个出口，checkpoint 现在没人管。四条约束写进契约注释：加密存储、按 manifest 的 `audit.retentionDays` 到期清除、不进追踪不进审计、控制台只能看到 `checkpoint_ref` 这个字符串，任何角色都看不到内容。

- **底座 v1 不提供 checkpoint 存储**，只存 `checkpoint_ref` 字符串。askdb 已有 LangGraph checkpointer，继续用自己的。等第三个智能体也在重复造存储时再把默认实现收进 SDK（先写重复代码，第三次再抽象）。Java 侧**待确认**：没有 LangGraph 对应物，starter 先只支持「从头重放 + 按 `idempotency_key` 跳过已完成的工具调用」，还是要求业务自己实现一对序列化回调——P0-11 开工前定。

- **恢复必然要重新换票**。`resume` 进来时原换票 token 一定已经过期（600 秒）。SDK 重新换票；用户会话也失效的用委托 token；两者都没有就返回 `RUN_RESUME_DENIED`，提示用户重新发起。不要在这里做静默降级。

- **`resume_token` 不是认证凭据**，它只证明「持有者看到过这次挂起」。谁有权恢复由 `approval_policy` 判定，和 `resume_token` 无关。不要用它替代鉴权。

- **幂等只保护到工具调用这一层**。`idempotency_key` 相同时返回原 run，不重新执行。但工具本身是否幂等由工具自己负责，底座不做去重。

## 验收标准

```bash
python3 -m jsonschema --check contracts/*.schema.json
npx @redocly/cli lint contracts/invoke.openapi.yaml
```

- [ ] 六个契约文件全部通过语法校验（与 P0-1 同一条命令）
- [ ] `suspend` 事件的三个 `reason` 取值各有一个正例通过 schema
- [ ] 反例被拒：`suspend` 缺 `run_id`、`reason` 为未知值、`resume` 请求 `reason=approval` 但缺 `decision`
- [ ] `final` / `error` 不带 `run_id` 时被拒
- [ ] `contracts/tests` 里有一条跨语言用例：构造一次 suspend 再 resume 的事件序列，Java 和 Python 模型解析结果一致
- [ ] 技术负责人评审通过，与 P0-1 一起打 tag `contracts/v1.0.0`

## 明确不做

- **不做工作流引擎**。不做条件分支、不做多步编排、不做 DAG。底座不决定下一步。
- 不做 checkpoint 的存储实现（见实现要点第四条）
- 不做控制台页面。「审批中心」在 P3-3：按 `subject_type` 分成工具审批 / 智能体审批 / 数据导出三组，外加一组「人工介入」（来自 run 的 `input_required`、`handoff`，没有审批单）。工具注册表拆到独立的「工具」页
- 不做 `handoff` 的落地。枚举值这次写进契约，接坐席系统、转人工会话管理都不在本期。控制台质量中心的「转人工率」指标在 handoff 落地前采不到数，P2-12 实现时要么留空要么撤掉，不要造假数据
- 不做通知渠道。ApprovalNotifier 在 P3-2
- 不写任何生成代码，那是 P0-2
