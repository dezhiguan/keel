# 元智能体第二版升级验收

2026-10-20（北京时间）已完成三个阶段：封存并正式发布 v1 基线、升级逻辑网关与技能、完整门禁后正式发布 v2。所有实现已提交推送，CI/CD 成功，最终两个实例均 Ready。

## 发布与部署

- 平台发布：`prod/v2`，`gatePassed=true`，发布者 `guandezhi`。
- 实测及发布源码：`b7d072817000b404859711e4bac4502f141c494e`；镜像 `meta-agent:b7d072817000`，实际摘要 `sha256:bf1cf6e547c504b1f48cf8fac52d91fe3f9861a16461ab8b014214e435d85198`。
- [CI 39319227420](https://github.com/dezhiguan/keel/actions/runs/39319227420) 与 [CD 39319227747](https://github.com/dezhiguan/keel/actions/runs/39319227747) 均 success，包括真实 PostgreSQL 回归及元智能体部署。
- 最终实例：`meta-agent-74585d6dbc-bcldf`、`meta-agent-74585d6dbc-rjkbd`，均运行同一镜像，Ready 2/2。
- 平台登记版本已读回为 v2，完整清单哈希 `09d52e5c0cbdd31752cae91e5661f10f81ba9a82a30eda659b34b940d380a38b`。沿用原内部元智能体的 dev 运行环境；prod/v2 是平台正式发布记录，本次未新建另一套生产 Deployment。
- [结构化验收证据](evidence/meta-agent-v2.json) 保存实际评测、发布返回、镜像摘要、实例及链路证据；不含认证信息。原始分阶段证据保留在应用节点 `/tmp/keel-meta-evidence/`。

## 完整基线与门禁

| 阶段 | 平台运行 | 用例 / 维度 | 总分 | 原16维度回归 |
| --- | --- | --- | --- | --- |
| 正式 v1 | `ev_c3d82f4aaa344010bc5933ea82a17af0` | 52 / 16 | 1.00 | 基线 |
| 网关候选 | `ev_dba52c3e03094b28b8df223fc40d648f` | 54 / 18 | 1.00 | 0 个百分点 |
| 最终技能候选 v2 | `ev_6bc82b941cb44bf98b63775ea56d5736` | 55 / 19 | 1.00 | 0 个百分点 |

三轮均为 DONE、complete=true、passed=true，无新增失败。门槛保持 minScore=0.99、每维度最大回归2个百分点。原52条核心用例及16标签保留；第二版仅将模型接入期望显式改为逻辑模型，另加生成和成功评审题，最后合入1条带技能来源的用例。技能题沿用平台缺省标签 untagged，单独计1条，不替代核心回归。

- 原题集 `meta-agent/dev-employee-specs` 不覆盖；网关与技能分别使用 `meta-agent/v2-gateway`、`meta-agent/v2-development`，授予技能前先切换题集，避免污染已测网关题集。
- 正式 v1 保留旧镜像 `meta-agent:44b8bf07ff73`、draft v2 / revise v1 和不可变清单；此前历史运行 `ev_0eb6ff36ead643998910321a0e8e1235` 也保留，但不冒充正式发布记录。
- v2 实测并发布的提示词为 draft v3、revise v1、generate v2、review v1、expand v2。发布使用该完整评测绑定的镜像与提示词版本，未使用部分评分或旧候选评测。
- [最终 Langfuse 实验](https://jp.cloud.langfuse.com/project/cmutztp4u0113ad0dmcu73du3/datasets/cmvflr6f10010ad0embfj3fav/runs/e77b3b52-0bde-4e8e-bb98-51a86b36f269)。

## 工程与底座适配

- 元智能体按正式手写项目维护，入口、需求、编排、生成、校验、评审、交付、工具、状态、提示词、技能、评测和运维按职责分开；入口不堆叠业务实现。工程约束已写入仓库 AGENTS.md。
- 清单显式 kind=workflow，所有模型调用经过 ctx.llm，使用 `chat-default`，生成项目继承逻辑入口。五个模型步骤均使用版本化提示词。
- 固定授予并加载人工维护的 `keel-project-development v1`；toolsGranted=[]，casesMerged=1，不增加工具权限。技能只提供开发方法，不能覆盖授权、审计、审批或发布门禁。
- 运行准备脚本注入已登记服务身份，并为虚拟 Key 授权逻辑入口及当前路由目标，保留 fallback 和限额。真实 Key 日预算仍为10元；历史清单预算声明50元保持原样，本次未上调实际预算。
- 纯聊天确认保存需求与本地骨架，交接既有控制台研发任务/H1；远程交付走受控 workflow，不调用已不适配服务身份的旧 CLI 管理写操作。
- 新增候选配置更新接口，先改契约再同步模型/类型，只能更新未发布候选，并通过 expectedHash、归属权限、活动评测锁和技能授权检查。不可变 v1 不覆盖，身份、预算及权限边界不扩张。
- 发现历史注册清单和旧实际镜像的工具/审计/门禁漂移，通过真实 ADMIN 显式读取运行清单对齐 v2；保留 v1 原快照及漂移记录。发布拒绝将旧评测绑定到较新的候选。

## 真实运行验收

- 服务身份真实签名读取技能 v1 成功；正文规范化后的 SHA-256 为 `ee0ef8a2324d9e7843a30be279e52632441323f1ea3738f08c1eab214dd8242a`。
- 技能题真实 trace `68387d7105e6fbae30bbc609e5d6c3d5` 包含技能名称/version=1；请求模型为 chat-default，实际网关选择 qwen3.8-flash，可关联 requestId、路由 primary 和价格版本2026-10-18。
- 双实例运行 `9b59aecbf7fc41ca8262bcbcb51b379a`：A 创建、B 读取；同一令牌并发恢复返回200/409，唯一消费成功；跨实例确认后 DONE，未创建远程仓库。
- 恢复 trace `2b703e337f42a398d1a649061efc9306` 可通过当前 insight 接口读取，关联 revise v1、chat-default、真实网关账单和 SETTLED 状态。
- 两实例并发调用实际 delivery 逻辑，结果一致；创建仓库、推送分支、创建PR各计1次。Git边界仅在隔离测试进程中模拟，不在生产进程替换，不将此项称为真实远程仓库交付。
- SDK 运行索引使用文件锁及原子替换，恢复令牌原子消费，需求状态原子落盘；追踪和审计暂存按 Pod 隔离。

## 测试与失败修复记录

可信远端隔离目录的元智能体、SDK 并发/恢复追踪/技能/评测专项共150项通过，仅有既有 Starlette 弃用警告；对应专项纳入远程 CI。本机环境缺少 pytest，完整 Python 结果以可信远端及 CI 为准。契约检查、前端类型检查和 Java 定向检查此前通过，完整服务端结果以本次成功 CI/CD 为准。两个既有 SDK loop 用例使用过期 FakeLlm 签名，先前扩跑失败，本次未改动，也不宣称完整 SDK Python 套件全部通过。

以下失败均保留原始证据，修复后重新提交、部署并完整评测；未降低评分规则或发布门槛：

| 阻断 | 原因与处理 |
| --- | --- |
| 候选登记/数据库 CI | 清单哈希前缀超出 VARCHAR(64)，以及事务 now() 导致读回旧快照；改为64位十六进制和写入时 clock_timestamp()，真实 PostgreSQL 回归通过。 |
| 双实例恢复追踪 | Context 在真实 resume span 创建前使用随机 trace_id；调整创建顺序，覆盖 final/suspend/error。最终恢复链路和账单可关联。 |
| `ev_55b021847a354c4bb21a47595e989eb2` | 扩充模型输出 b-/f-编号和 null/字符串 input，IncompleteExperiment；提示词明确已有契约并提供字段级修复反馈，保留三次上限和全部接受条件。 |
| `ev_1666a94a9e924a49ac485474b6c2a13c` | 生成测试文件语法错误，IncompleteExperiment；提供 AST 行列与原因，首次生成修复格式和解析器一致，generate v2 后完整54条通过。 |
| `ev_94b20ddb5e20499e9f2efd438c82b24e` | Langfuse 将技能题 JSON 字符串还原成对象，worker只取 text 导致空需求；修复输入转换，保留显式空字符串，增加表示等价及空输入测试。技能v1、用例与评分器不变，最终55条通过。 |

临时追踪验收脚本的旧路径404已改为 `/api/v1/insight/traces/{traceId}` 后复验通过，不据此修改线上服务或扩大权限。

## 保留边界

两个实例共享原单节点 local RWO 卷，提供进程冗余，不提供节点容灾。保持 CREATE、人工审批及受控发布边界；本次不扩展 CHANGE、知识库、自主发布或强行引入无业务需要的记忆/通用 agent loop。后续升级继续按手写项目规范，保留基线、运行证据和门禁后发布。
