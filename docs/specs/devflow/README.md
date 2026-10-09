# 全流程智能体化：任务拆解

来源：`docs/design/Keel-全流程智能体化方案.html` 第 06、08、10 节，前端见 `docs/console/Keel-全流程智能体化控制台.html`。
每条任务一份 spec，放本目录，文件名 `DF-{n}-{短名}.md`。动手前先读 spec。

执行者沿用 `docs/TASKS.md` 的判断标准：安全与一致性关键、首次对接外部系统的交给 Cursor；照原型实现页面、薄封装交给 Codex。

## 当前进度（2026-10-09）

| 状态 | 任务 |
|---|---|
| 已完成（控制台） | [DF-9d 审计](DF-9d-audit.md)：动作选 `config.change` 时按 `devflow.stage`、`devflow.takeover`、`drift` 筛选，行上显示 `payload.kind` |
| 已完成（控制台） | [DF-9d 评测](DF-9d-eval.md)：结论旁显示隐藏考题聚合分和分差，分差超过 0.10 标红。待评测确认链到关口页。没有聚合分时显示「—」 |
| 已完成 | [DF-5d 任务账本](DF-5d-ledger.md)：确认后以服务身份开研发任务并回报 SPEC，停在 H1。不注册、不跑门禁、不申请合并 |
| 已完成 | [DF-5c 建仓库并开 PR](DF-5c-repo.md)：确认后经 Git 服务建库、推 `draft`、开 PR。不合并。未配置地址时不访问网络 |
| 已完成 | [DF-5b 项目骨架](DF-5b-scaffold.md)：`keel new --from-spec` 按已确认的需求单复制模板。沙箱里跑测试仍不做 |
| 已完成 | DF-5a 元智能体第一步：需求单与岗位说明书（本地 `keel dev` 跑通） |
| 已完成（控制台 mock） | DF-9a 研发任务页：看板、详情、人工接管；批次和规则页按原型可点。keel-server 归 DF-2 / DF-11 |
| 已完成（占位） | [DF-9d 共享服务](DF-9d-services-sandbox.md)：平台组件表加研发沙箱。用量未接配额，空数据用占位。其余页面仍待做 |
| 已完成（控制台） | [DF-9b 智能体页](DF-9b-agents-console.md)：分类加元智能体，卡片带来源，谱系视图，新建向导加「由智能体生产」。未注册员工占位 |
| 已完成（控制台） | DF-9d 链路追踪：按研发任务筛选，行上标来源。没有任务号时用来源占位。其余 DF-9d 页面仍待做 |
| 已完成（控制台 mock） | [DF-9c 审批中心](DF-9c-approvals.md)：加「研发任务」分组和 H1 / H2 / H4 / 协作 review 关口处理页，审批单和挂起运行带 `devflowJobId` / `devflowGate`。review、seed-cases 接口只在 MSW，keel-server 归 DF-2 |
| 已完成 | [DF-1 契约](DF-1-contracts.md)：`DEVFLOW_*` 错误码、审批 `source=devflow`、审计 `kind`、阶段回报 / 沙箱 / 隐藏考题路径。看板等路径此前已由控制台切片写入 |
| 已完成 | [DF-8 隐藏考题](DF-8-holdout.md)：按比例切分，只有配置的 CI 客户端能读正文和回写聚合分。由智能体生产的员工发布前要有已批准的合并审批 |
| 已完成 | [DF-4 工具登记](DF-4-tools.md)：14 个共享工具写入注册表，`git.pr.merge` 绑定 7 天的发布审批。授权等员工入库后由人来写 |
| 已完成 | [DF-3 沙箱](DF-3-sandbox.md)：命名空间、配额、拒绝公网的网络策略、沙箱 Job。假薄网关和假 Langfuse 只在 Pod 内应答。不克隆仓库 |
| 已完成 | [DF-2 账本](DF-2-ledger.md)：任务落 PostgreSQL（V10），接管 / 交还 / 阶段回报 / 谱系 / 审计。隐藏考题切分已在 DF-8 |

## D0 底座前置（不在本目录写 spec，归属原任务）

| ID | 内容 | 现状 | 归属 |
|---|---|---|---|
| D0-1 | `keel register` / `release` / `eval` / `gate` | CLI 只有 new、dev | P0-10 续 / P2 |
| D0-2 | keel-audit 落 PostgreSQL | 哈希链在进程内存 | P1-11 续 |
| D0-3 | 委托授权 `/oauth/delegation-token`；挂起超过 10 分钟可恢复 | 请求体已按 auth-gateway 源码接入。没有 consent 时仍返回 `RUN_RESUME_DENIED` | P0-5 续 / P3-1 续 |
| D0-4 | 工具注册表的服务身份读接口、授权写入 | 智能体断言可读目录；`POST /tools/{name}/grants` 只认控制台用户 | P2 tool 模块 |
| D0-5 | 薄网关加编码模型 | `qwen3.8-flash`，2026-10-08 百炼中国站标准价 | P1-2 续 |

## 本方案任务

| ID | 任务 | 执行者 | 前置 | 期 |
|---|---|---|---|---|
| DF-1 | 契约：console-api 的 devflow 接口；agents 加 `layer`、`devflowJobId`；审批待办加 `source`；错误码 `DEVFLOW_*`；追踪属性 `keel.devflow.job_id` | Cursor | — | D1 |
| DF-2 | keel-server devflow 模块：V10 表（V8 已被委托同意占用）、状态机、接管 / 交还、谱系校验、审计。目录读已在 D0-4 完成。关口只读、种子用例条数、审批 `source`、审计 `kind`、`devflowCost` 已接上 | Cursor | DF-1 D0-4 | D1 |
| DF-3 | 沙箱：V11 运行记录、命名空间配额与只出 DNS 的网络策略、SandboxService、假薄网关 / 假 Langfuse sidecar。不克隆仓库 | Cursor | DF-2 | D1 |
| DF-4 | Git / CI MCP 与分支保护已在 D0 落地。V12 登记 14 个共享工具，`git.pr.merge` 绑定「智能体发布审批」（7 天，角色 ADMIN）。不写授权 | Codex | D0-4 | D1 |
| **DF-5** | **元智能体 meta-agent**，分四步交付，见下 | Cursor | 各步不同 | D1–D2 |
| DF-6 | meta-agent 生产 spec-agent、dev-lead（人机协作） | meta-agent + 人 | DF-5d | D2 |
| DF-7 | meta-agent 生产 dev-agent、eval-agent、code-review、ci-doctor、release-agent | meta-agent + 人 | DF-6 | D2 |
| DF-8 | 隐藏考题切分、`keel gate --holdout`、发布前核对已批准的 `git.pr.merge`。评测列表的 `EvalResult.holdout` 和 Langfuse 上报未做 | Cursor | DF-2 D0-1 | D2 |
| DF-9a | 控制台：研发任务页（看板、详情、人工接管） | Codex | DF-1 | D1 |
| DF-9b | 控制台：智能体页分类 / 来源 / 谱系；新建向导"由智能体生产" | Codex | DF-1 | D1 |
| DF-9c | 控制台：审批中心"研发任务"分组与关口处理页 | Codex | DF-1 P3-1 | D1 |
| DF-9d | 控制台：总览、共享服务、链路、评测、工具、审计、模型网关的增强 | Codex | DF-1 | D2 |
| DF-10 | 业务智能体试产（release-notes 全托管、oncall-handoff 协作）与指标复盘 | 研发流水线 + 人 | DF-7 DF-8 DF-9 | D3 |
| DF-11 | 批次、批量确认、改造任务 | Cursor | DF-10 | D4 |

## DF-5 元智能体分步

meta-agent 是整个体系里唯一手写的智能体。底座能力是逐步补齐的，所以它也分四步交付，每一步都能独立验收。

| 步 | 内容 | 前置 | spec |
|---|---|---|---|
| DF-5a | 需求 → 需求单 + 岗位说明书草稿（manifest 按契约校验）→ 需求确认挂起 → 确认或按意见修改 | 无（本地 `keel dev`） | [DF-5-meta-agent.md](DF-5-meta-agent.md) |
| DF-5b | 生成项目骨架（`keel new --from-spec`）。沙箱 pytest 与协议自检仍不做 | DF-3 | [DF-5b-scaffold.md](DF-5b-scaffold.md) |
| DF-5c | 建仓库、推 `draft`、开 PR。不合并，也不在冲突时挂起问人 | DF-4 | [DF-5c-repo.md](DF-5c-repo.md) |
| DF-5d | 确认后写入研发任务账本并回报 SPEC，停在 H1。注册、门禁、合并申请仍不做 | DF-2 D0-1 D0-3 DF-8 | [DF-5d-ledger.md](DF-5d-ledger.md) |

三条可以并行的线：**契约线** DF-1 → DF-9*；**元智能体线** DF-5 的四步里，注册、门禁和合并申请还没做；**底座线** D0-* → DF-2 → DF-3 / DF-4。
