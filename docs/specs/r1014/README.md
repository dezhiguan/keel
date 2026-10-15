# 2026-10-14 评审：执行计划

设计依据：`docs/design/Keel-智能体底座设计-20261014.html` 第三部分（第 22～32 章）；界面依据：`docs/console/Keel-控制台前端-20261014.html`（第 3 版，紫色"提案"标签即本计划要做的功能）。

按重要程度排序，**上一阶段的 P 任务没完成，不开下一阶段**。同一阶段内没有依赖关系的可以并行。

## 开工规则

- 每个任务先在本目录写 spec（`docs/specs/_TEMPLATE.md`），再改代码，不要拿这张表直接动手。
- 跨语言的数据结构先改 `contracts/`，新字段一律可选，不升 v2。
- 外部接口（Langfuse、薄网关、auth-gateway）按 `.cursor/rules/external-apis.mdc`，规则里没有的先停下来问。
- 测试按 `.cursor/rules/testing.mdc`：只跑改动模块；`quota/`、`approval/`、`chain/` 要求 100% 分支覆盖。
- 标"待拍板"的任务，先等人决定，不要猜一个实现。
- **控制台对齐**：每个任务开工前先查 [`ui-checklist.md`](ui-checklist.md) 里有没有自己的编号。有的话，spec 的"界面对齐"一节要写明原型位置，验收时把对应行一起做完并更新状态。后端任务同样适用。

---

## 阶段 0 · 立即修（线上缺陷，最高优先级）

| ID | 任务 | 前置 | 验收 |
|---|---|---|---|
| R0-1 | 薄网关：同别名重建 Key 后，旧 Key 必须永久失效（`KeyDirectory.byToken` 比对 token；Key 带版本号） | — | 旧 Key 在重建、重启后都返回 401 `LLM_KEY_BLOCKED`；有回归用例 · ✅ r1014-r0-1 |
| R0-2 | 薄网关：额度原子预留 → 按实际 usage 结算 → 退回差额；同一 requestId 只结算一次；Redis 原子操作，跨副本一致 | — | 并发压测（如 50 并发、预算只够 10 次）不超日预算；重复结算请求不重复扣费 · ✅ r1014-r0-2 |
| R0-3 | 薄网关：usage 缺失不记 0，进入"待计费"状态并告警 | R0-2 | 模拟上游不返回 usage，账单状态为待计费，费用不为 0 也不丢失 · ✅ r1014-r0-3 |
| R0-4 | 模板 Dockerfile 不再从公网 PyPI 安装 `keel-sdk`；在 R2-1 就绪前改为从仓库内构建的 wheel 安装 | — | 断开公网 PyPI 时 5 个模板仍能构建；模板里不再出现裸的 `pip install keel-sdk` · ✅ r1014-r0-4 |
| R0-5 | keel-server：总览 `range=24h|7d|30d` 真正生效（`AgentUsageService` 不再写死 24 小时） | — | 三个窗口返回的调用数、成本不同且与数据一致；有单测 · ✅ r1014-r0-5 |

## 阶段 1 · 成为真智能体（核心能力）

| ID | 任务 | 前置 | 验收 |
|---|---|---|---|
| R1-1 | 契约：manifest 加 `spec.kind`（agent / workflow）；追踪属性 `keel.loop.step`；错误码 `AGENT_MAX_STEPS_EXCEEDED`、`PROMPT_VARIABLE_MISSING` | — | `contracts/tests` 通过，Python / Java 模型同步生成 · ✅ r1014-r1-1 |
| R1-2 | SDK：`ctx.llm.chat` 支持可选 `tools`，返回内容 + tool_calls；现有调用方不变 | — | 原有用例全过；新增用例：模型返回 tool_calls 时能拿到结构化结果 · ✅ r1014-r1-2 |
| R1-3 | SDK：`ctx.loop` —— 工具调用循环；停止条件：完成、`maxSteps`、预算、同工具同参数连续 2 次；循环内高风险工具存 checkpoint 后 `suspend`，恢复后接着跑；每步一个 span | R1-1 R1-2 | 用假薄网关的集成测试覆盖四种停止条件和一次挂起恢复；超步数返回 `AGENT_MAX_STEPS_EXCEEDED` 并发 `event: error` · ✅ r1014-r1-3 |
| R1-4 | SDK：运行内上下文由 loop 自动累积；会话记忆（按 session，有 TTL）先用 keel-lite 本地实现，接口为 `ctx.memory`；评测流量禁止写入 | R1-3 | 两轮对话第二轮能引用第一轮内容；`eval_run_id` 非空时写入被拒 · ✅ r1014-r1-4 |
| R1-5 | 模板：新增 `loop-agent` 并设为默认；`graph-agent` 改成真实 LangGraph 示例；普通代码模板 manifest 写 `kind: workflow` | R1-3 | `keel new` 默认生成 loop-agent，自带冒烟用例能跑通 · ✅ r1014-r1-5 |
| R1-6 | 元智能体产出修正：生成的 manifest 声明 `prompts.items`、knowledge、guardrails；交付保留 `prompts/`；生成代码只能用 `ctx.prompt()`；code-review 检查项加"硬编码提示词" | — | 新交付的项目注册后，提示词出现在 Langfuse 和控制台提示词页；源码里没有大段提示词字符串 · ✅ r1014-r1-6（本地验收；云端写入待人工） |
| R1-7 | 元智能体：交付时"生成 → `sandbox.run` 跑测试 → 按报错修改"有界循环，最多 3 轮 | DF-3 沙箱 | 故意给一个会失败的需求，能看到修正轮次；3 轮不过则任务失败而不是交付 · ✅ r1014-r1-7 |
| R1-8 | keel-server 注册自检加"智能体要素检查"：`kind: agent` 缺工具调用 / loop / 提示词 / 评测之一则拒绝注册 | R1-1 R1-3 | agent 型缺提示词时注册被拒并给出缺项；workflow 型可注册 · ✅ r1014-r1-8 |

## 阶段 2 · 独立成库

| ID | 任务 | 前置 | 验收 |
|---|---|---|---|
| R2-1 | 发布到当前 GitHub：Java starter → GitHub Packages（Maven）；Python SDK → GitHub Release 附件（wheel，tag `sdk-v1.x.y`），GitHub Packages 不支持 PyPI。版本对齐契约（`keel/v1` → `1.x`）；CI 打 tag 自动发布；模板按 Release 地址 + 访问令牌安装 | R0-4 | 新机器只配 GitHub 令牌就能装 SDK、引入 starter；不访问公网 PyPI 的 `keel-sdk` · ✅ r1014-r2-1 |
| R2-2 | `keel new` 生成完整独立仓库：Dockerfile、依赖锁定、契约 schema、CI（单测 → gate → 构建镜像 → register / release）；不引用底座源码路径 | R2-1 | 在与底座无关的新仓库里，从 `keel new` 到 staging 注册全程不需要底座源码 · ✅ r1014-r2-2 |
| R2-3 | Git 服务补写 `.github/workflows` 的权限，交付的仓库 CI 开箱生效 | — | 新交付仓库首次推送即触发 CI · ✅ r1014-r2-3 |
| R2-4 | SDK 独立运行模式 `KEEL_MODE=standalone`：模型直连 OpenAI 兼容地址、提示词读本地、审计写 keel-lite、追踪可选 | R1-3 | 不注册、不连薄网关也能在本地完整跑一次 loop-agent；生产 profile 下禁止开启 · ✅ r1014-r2-4 |
| R2-5 | meta-agent 迁到独立仓库，底座仓库只留示例 | R2-2 | meta-agent 从新仓库构建部署，线上功能不变 · ✅ r1014-r2-5 |

## 阶段 3 · 补齐生命周期（实测为主）

| ID | 任务 | 前置 | 验收 |
|---|---|---|---|
| R3-1 | 用测试智能体实跑 staging → `keel gate` → `keel release` → prod，含一次门禁不过被拦 | 阶段 0 | 按 `docs/qa/` 的格式出验收报告，有 trace、审计、发布记录作证据 · ✅ r1014-r3-1（脚本与清单已交，线上未跑） |
| R3-2 | 实测高风险工具挂起审批 → 批准 → 恢复执行（含超 10 分钟重新换票） | — | 报告中有挂起、审批、恢复三段审计和同一 run 的链路 · ✅ r1014-r3-2（脚本与清单已交，线上未跑） |
| R3-3 | 质量中心：用户反馈、点踩率、坏案例加入评测集（替换占位页） | — | 点踩的调用能一键进数据集，下次回归包含该用例 · ✅ r1014-r3-3 |
| R3-4 | 智能体删除的后端接口（仅已下线可删，审计保留） | — | 未下线删除返回 `AGENT_NOT_RETIRED`；删除后审计和链路仍可查 · ✅ r1014-r3-4 |
| R3-5 | 元智能体完整生产第一名研发员工（如 spec-agent），走到 prod 和 7 天观察 | 阶段 1 R2-2 | 线上智能体目录出现来源为"meta-agent"的员工，生产线"本月上线"≥ 1 · ✅ r1014-r3-5（脚本与清单已交，线上未跑） |

## 阶段 4 · 扩展能力

| ID | 任务 | 前置 | 验收 |
|---|---|---|---|
| R4-1 | 薄网关 P1：账单落 PG `keel_llm.llm_spend`（每次尝试一行，带 requestId / attemptId / traceId / 价格版本），稳定游标分页；真实 SSE 流式及取消时结算；RPM / TPM / 并发上限 | 阶段 0 | 账单可全量拉取并与预算对账；流式请求可取消且费用正确 · ✅ r1014-r4-1 |
| R4-2 | keel-server `metering/` + `usage_daily`；总览加"累计"和时间窗；指标显示口径与来源；未接入显示"未接入" | R4-1 R0-5 | 累计值 = 已封账日汇总 + 今日实时；与网关账单一致 · ✅ r1014-r4-2 |
| R4-3 | 控制台按原型第 3 版改造：菜单名称、各页 tab 化、平台与能力服务、模型与成本五个 tab、链路详情显示网关尝试、智能体详情的运行方式 / 要素检查 | R4-1 R1-8 | 页面与原型一致；`vitest related` 通过；数据全部走 keel-server 接口 · ✅ r1014-r4-3 |
| R4-4 | 薄网关 P2：路由策略（硬约束过滤 + 均衡 / 成本 / 延迟）与试算、按渠道熔断、策略版本与回滚、告警规则进总览。**先把新管理接口写进技术文档和 `external-apis.mdc`** | R4-1 | 故障注入只切换一次；鉴权失败、预算不足不降级；试算结果与实际路由一致 · ✅ r1014-r4-4 |
| R4-5 | 结构化提示词：分段、变量 schema、输出契约、试运行。结构信息存 Langfuse 提示词 config 的 `keel` 命名空间，正文仍是编译后的 text / chat。**先实测** `POST /api/public/v2/prompts` 的 config 传法并读回，再改 `external-apis.mdc` 里"config 只认三项"的规则 | — | 实测记录写进 spec；删除一条约束时提示对应评测维度；缺变量报 `PROMPT_VARIABLE_MISSING`；SDK 读到 `keel` 段时不当作模型参数 · ✅ r1014-r4-5 |
| R4-6 | 技能：注册表与授权（三张表）、manifest `spec.skills`、SDK `ctx.skills` 按需加载 | R1-3 | 加技能不会自动授予工具；技能用例并入使用方回归 · ✅ r1014-r4-6 |
| R4-7 | 记忆服务 keel-memory（PG + pgvector）：用户记忆、按层策略、可查可删 **【待拍板：合规口径】** | R1-4 | 元智能体写记忆被拒；用户删除后不可再召回 · ✅ r1014-r4-7（会话原文不入库；未做向量召回） |
| R4-8 | 渠道发布：托管页面 + 嵌入代码 + 网页 SDK；渠道表与发布凭证；外部用户身份三种方式。托管页面先用底座域名的子路径（如 `https://keel.ragforge.net/a/{agent}`），与控制台同域，控制台会话 Cookie 必须限定路径且 HttpOnly，托管页面读不到 | keel-gateway 轻量版 | 业务网页嵌入后能对话、挂起审批、点赞点踩；未登记域名被拒（`CHANNEL_ORIGIN_DENIED`）；托管页面拿不到控制台 Cookie · ✅ r1014-r4-8 |
| R4-9 | 小程序 SDK **【先核实小程序流式方案】** | R4-8 | 推迟 · 小程序流式方案以后再核实，本轮不做 |

---

## 决策记录

| 事项 | 结论（2026-10-15） | 影响 |
|---|---|---|
| 私有包仓库 | 用当前 GitHub：Maven → GitHub Packages；Python → GitHub Release wheel | R2-1 |
| 结构化提示词存放 | 存 Langfuse 提示词 config 的 `keel` 命名空间，先实测再改规则 | R4-5 |
| 托管页面域名 | 先用底座域名的子路径 | R4-8 |
| Java 侧的图 | 暂不做；Java 智能体只用普通代码和 `ctx.loop`（starter 侧同步实现） | 原 R4-10 取消 |
| 会话原文、用户记忆存 Keel 库的合规口径 | 会话原文留在 keel-lite。用户记忆正文可查可删，审计只记条数和类别。不做向量召回 | R4-7 |
