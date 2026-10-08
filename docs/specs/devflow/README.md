# 全流程智能体化：任务拆解

来源：`docs/design/Keel-全流程智能体化方案.html` 第 06、08、10 节，前端见 `docs/console/Keel-全流程智能体化控制台.html`。
每条任务一份 spec，放本目录，文件名 `DF-{n}-{短名}.md`。动手前先读 spec。

执行者沿用 `docs/TASKS.md` 的判断标准：安全与一致性关键、首次对接外部系统的交给 Cursor；照原型实现页面、薄封装交给 Codex。

## 当前进度（2026-10-08）

| 状态 | 任务 |
|---|---|
| 进行中 | DF-5a 元智能体第一步：需求单与岗位说明书（本地 `keel dev` 跑通） |
| 已完成（控制台 mock） | DF-9a 研发任务页：看板、详情、人工接管；批次和规则页按原型可点。keel-server 归 DF-2 / DF-11 |
| 可以立刻开工 | DF-1 契约（页面用到的 jobs 路径已在 DF-9a 写入 console-api，其余字段仍在 DF-1）；DF-9b 智能体页增强（只依赖 DF-1） |
| 等前置 | 其余，见下表"前置" |

## D0 底座前置（不在本目录写 spec，归属原任务）

| ID | 内容 | 现状 | 归属 |
|---|---|---|---|
| D0-1 | `keel register` / `release` / `eval` / `gate` | CLI 只有 new、dev | P0-10 续 / P2 |
| D0-2 | keel-audit 落 PostgreSQL | 哈希链在进程内存 | P1-11 续 |
| D0-3 | 委托授权 `/oauth/delegation-token`；挂起超过 10 分钟可恢复 | P3-1 目前对这种情况直接返回 `RUN_RESUME_DENIED` | P0-5 续 / P3-1 续 |
| D0-4 | 工具注册表的服务身份读接口、授权写入 | `/api/v1/tools` 只认控制台登录 | P2 tool 模块 |
| D0-5 | 薄网关加编码模型 | 只有 qwen-plus、deepseek-v3；单价待按厂商当天价目核实 | P1-2 续 |

## 本方案任务

| ID | 任务 | 执行者 | 前置 | 期 |
|---|---|---|---|---|
| DF-1 | 契约：console-api 的 devflow 接口；agents 加 `layer`、`devflowJobId`；审批待办加 `source`；错误码 `DEVFLOW_*`；追踪属性 `keel.devflow.job_id` | Cursor | — | D1 |
| DF-2 | keel-server devflow 模块：V8 表、状态机、接管 / 交还、谱系校验、审计；服务身份的工具目录读接口 | Cursor | DF-1 D0-4 | D1 |
| DF-3 | 沙箱：命名空间、配额、网络策略、SandboxService、假薄网关 / 假 Langfuse sidecar | Cursor | DF-2 | D1 |
| DF-4 | Git / CI MCP 服务、分支保护、工具登记 | Codex | D0-4 | D1 |
| **DF-5** | **元智能体 meta-agent**，分四步交付，见下 | Cursor | 各步不同 | D1–D2 |
| DF-6 | meta-agent 生产 spec-agent、dev-lead（人机协作） | meta-agent + 人 | DF-5d | D2 |
| DF-7 | meta-agent 生产 dev-agent、eval-agent、code-review、ci-doctor、release-agent | meta-agent + 人 | DF-6 | D2 |
| DF-8 | HoldoutService、`keel gate --holdout`、发布校验 | Cursor | DF-2 D0-1 | D2 |
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
| DF-5b | 生成项目骨架（`keel new --from-spec`），在沙箱里跑单测和协议自检 | DF-3 | 同上，第 M2 节 |
| DF-5c | 建仓库、推分支、开 PR；人机协作 review | DF-4 | 同上，第 M3 节 |
| DF-5d | 注册 staging、跑门禁、申请合并（发布审批）；接 devflow 任务账本 | DF-2 D0-1 D0-3 DF-8 | 同上，第 M4 节 |

三条可以并行的线：**契约线** DF-1 → DF-9*；**元智能体线** DF-5a 现在就能做；**底座线** D0-* → DF-2 → DF-3 / DF-4。
