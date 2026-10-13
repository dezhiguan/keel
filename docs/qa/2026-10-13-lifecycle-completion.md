# 生命周期剩余问题修复与远程复测（2026-10-13）

## 范围

针对 [首轮报告](2026-10-13-lifecycle-qa.md) 的四项待办，完成代码修复、测试、提交、远程部署及真实调用复测。测试对象为 `qa-lifecycle-1013`，Python / code / echo，环境 dev，模型 qwen-plus，日预算 ¥1，授权工具 git.pr.diff。使用用户授权账号；凭据未写入代码或报告。

本轮覆盖 Python 代码智能体；Java 评测执行器、RAG 接入和生产环境发布不在本轮验收范围，不声称通过。

## 四项待办

| 待办 | 修复 | 线上证据 |
| --- | --- | --- |
| 真实评测及任务持久化 | PostgreSQL 任务 + 独立 Kubernetes Job，使用项目评分器和 Langfuse SDK 实验；禁止假分 | 3 个独立实验，每次 4 条用例；首轮 0.75 / 不通过，后两轮 1.00 / 通过；Langfuse 读回 12 条真实评分 |
| 共享工具真实执行 | 部署 git-ci，SDK 服务断言、授权与环境检查、真实 tool span 和审计；TLS 透传修复出口网络 | 两次读取 dezhiguan/askdb PR #2，diff 长度 28,514 字符；工具 span 与审计可关联；匿名 401、未授权工具 403；工具统计读真实审计 |
| 自检结果持久化及重跑 | 保存探针结果与时间，绑定注册版本哈希；处理 JSONB 字段重排；等待云端追踪索引并重试瞬时读失败 | 10:18:54 健康、协议、追踪、审批绑定均通过；刷新及再次重启后 checkedAt 和结果一致，探针 trace bb9e28a3bbf293f1497ce2a3f2f738b9 |
| 审计真实账号与环境 | ConsolePrincipal 真实身份，保存提示词传入编辑环境，发布使用实际环境 | v3 保存为 guandezhi / test，生效为 guandezhi / dev；调试与自检为 guandezhi / dev；工具为服务身份；审计链校验完整 |

## 真实评测与重启

数据集 `qa-lifecycle-1013/smoke`，4 个用例 ID 保持不变，更新预期和 tags 时未删除历史实验。

| 任务 | 分数 / 门禁 | Langfuse 实验 |
| --- | --- | --- |
| `ev_d0614544e56e415185dfbac6314ce1dd` | 0.75 / 不通过，1 个真实失败 | `2ce08705-f1f6-491a-8af3-b45672661290` |
| `ev_ea75ee6732ef4fe9ba3d03eea8d2198d` | 1.00 / 通过，lifecycle 3 条 + smoke 1 条 | `b79c2cb9-414b-40e0-9df9-1b5ca889df48` |
| `ev_325b843c36b1446bb509c206eccf0614` | 1.00 / 通过，运行中重启后仍可查 | `c79111a1-37d2-450d-86c7-aa691b8f8bff` |

第三轮 10:03:23 观察到 RUNNING，10:03:33 发起 keel-server 滚动重启，任务于 10:03:38 完成。重启后同一任务返回 DONE。集群总共 3 个 Job，每个仅成功 1 次，没有重复运行。第一轮完成记录在重启后连续 5 次查询均返回 200 / DONE。

首轮故意保留默认 ping 用例的旧预期，提示词返回 QA1013_V2_OK，评分器判其为 0；其余 3 条为 1。随后提示词 v3 改为 QA1013_COMPLETE_OK、更新全部用例预期，按两个 tags 得分均为 1。Langfuse experiment-items 读回首轮 [1,1,1,0]、后两轮各 [1,1,1,1]，并非只信控制台或任务表。

## 调试、工具和追踪

- 最终调试返回 `QA1013_COMPLETE_OK`，trace `32dc64d93a43cc1c7960582cc60783aa`；generation 关联 answer v3，40 输入 + 7 输出 token，¥0.000046，与薄网关一致。
- 共享工具 trace：`5c6f1162246ae07b0d904ca6a90e828c`、重启后 `6371db97233566731f104f20fa6e3362`。每条包含 agent.run + git.pr.diff tool span、父子边、真实耗时、allowed 工具审计与 guandezhi 调试审计。
- git-ci 匿名直调 401，内部认证实际 PR diff 200；智能体调用未声明的 git.pr.comment 为 403 / TOOL_NOT_GRANTED，denied 审计 trace `9181dc833f7443b1a5f41a2abab51a9d`。没有创建 PR、评论或合并。
- 工具使用计数：24 小时总量 2，该智能体 dev 的 30 天调用 2；denied 请求未计入。详情依赖表补充实际调用次数列。
- 端到端 TLS 保留原域名证书校验，私网中继只允许 Langfuse Japan 和 GitHub API；不解密、不记录凭据。

## 调用次数和费用

验收开始时普通调用累计 7、模型账单 11 条 / ¥0.000479。收尾普通调用累计 14、模型账单 43 条 / **¥0.001840**，控制台金额与账单相加完全一致。新增 32 条模型账单 / ¥0.001361，可分为 15 次自检探针、12 次评测、5 次普通模型调用。自动验收的普通调用是 2 次模型对话 + 2 次纯工具调用；期间另观察到 3 次普通模型调用，纳入真实总量，不伪装为本轮自动用例。

普通业务调用次数来自真实调试/调用记录；评测和自检探针单独标记，不混入普通调用数。薄网关费用包含真实模型消费，含评测和探针；纯工具调用未产生模型费用。微额人民币显示保留精度。

发布门禁的 staging/prod 流程未执行；详情中的“评测门禁未运行”不作为本轮 dev 手动评测结果。本轮未发布到生产。

## 测试与部署

- 前端：152 项测试通过，类型检查与生产构建通过。
- Python：61 项原回归通过；补充共享工具启动修复后相关集成、评测、git-ci 回归 24 项通过。
- Java：本地单元及相关回归通过；本地无 Docker，数据库集成验证由远程 CI 的 PostgreSQL Testcontainers 完成。
- 数据库回归覆盖任务跨实例查询、并发任务唯一性、自检 JSONB 往返与配置变化失效、工具统计的 24 小时/30 天窗口、环境及 denied 过滤。
- 主要提交：`36ccfb8`（四项闭环）、`cf8e295`（后台漂移任务身份）、`94f7cd5`（TLS 转发）、`3822874`（共享工具启动校验）、`c18fd8e`（追踪等待）、`bd412e0`（JSONB 自检关联）、`e082ff1`（真实工具计数）。
- CI：[后端最新验收 CI，e082ff1](https://github.com/dezhiguan/keel/actions/runs/38335625673) 通过；首次依赖下载遇到 Maven Central HTTP 429，重跑通过。
- CD：[后端最新部署，e082ff1](https://github.com/dezhiguan/keel/actions/runs/38335625689) 通过。远程 server 镜像 e082ff1b8b75；SDK/echo 与 git-ci 镜像 3822874e4a0d；工具详情调用次数列随本报告单独发布。

## 收尾和证据

通过控制台下线测试智能体，重复下线返回 200；资源均为 REVOKED，调试返回 503 / GW_AGENT_OFFLINE。运行实例内的旧模型 Key 返回 401 / LLM_KEY_BLOCKED，旧服务断言返回 401 / AUTH_UNAUTHENTICATED。拒绝前后模型账单 requestId 集合完全一致，仍为 43 条 / ¥0.001840。仅将 agents/qa-lifecycle-1013 Deployment 缩为 0；历史追踪返回 200、评测任务仍为 DONE，Langfuse 数据集及实验保留。

本机证据位于 `/Users/amy/keel-qa-20261013/`：`completion-evidence.json`、`completion-experiment-items-final.json`、`completion-experiments-final.json`、`completion-spend-final.json` 及调试、自检、评测截图。证据不包含登录凭据、项目 Key 或实际 PR diff 原文。设计见 [P1-23](../specs/p1/P1-23-lifecycle-completion.md)。
