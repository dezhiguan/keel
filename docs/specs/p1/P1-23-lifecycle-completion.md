# P1-23 生命周期剩余阻塞闭环

## 目标与范围

修复 P1-22 报告的四项待办，提交、部署远程并实测。范围包括 contracts/console-api.openapi.yaml 与生成类型、keel-server 的 evaluation/insight/registry/prompt/integration/auth/provisioning/tool、数据库迁移与相应测试、SDK 的 eval/context、agents/echo、services/git-ci、console 的 eval/agents/prompts 页面、deploy/ci 与 GitHub CD，以及 docs/qa。

## 设计

- 评测任务保存 PostgreSQL，独立 Kubernetes Job 执行 Python 项目镜像中的 evals/scorers.py，调用登记环境 endpoint，使用 Langfuse 4.7+ 实验运行器记录真实实验及评分。项目镜像必须包含 SDK eval extra；缺少评分器或数据集时报错，禁止兜底假分。任务有 deadline、同 agent/env 并发保护、跨副本查询与重启恢复。结果包含实际案例数、各 tag 分数、失败用例、实验链接；低分是 DONE + passed=false，执行错误才 FAILED。候选与 production 有可用基线时对比回归。
- 重新自检 API 实际运行探针，将报告、时间及 manifest hash 保存数据库，详情读取最近有效报告；资源轮换后可再次执行，不硬编码未探测。
- 控制台请求使用 ConsolePrincipal 真实账号进行审计。提示词保存带可选 env（默认 dev，仅表示编辑环境），发布审计使用实际目标环境；服务导入仍按其实际环境记录。
- git-ci 服务增加健康探针、容器及远程部署；保留只读 PR diff 参数校验，来源凭据只放 Secret。SDK 工具调用产生真实 tool span，测试智能体通过 ctx.tools 调用授权的 git.pr.diff，并核对追踪、审计及权限拒绝路径。不会新建 PR、评论或合并。
- 服务断言从智能体 Secret 读取当前公钥，支持多副本及重启、凭据轮换；git-ci 调用另用内部凭据认证，不能匿名直调。环境、共享服务地址与 audience 由注册 Secret 注入。
- 数据集导入保留 tags 元数据，评分器按真实 tags 聚合。发现的 SDK 本地高风险工具审批路径补充同步 pending 审计，写失败时不得创建审批或执行工具。

## 验收

真实评测能产生新实验、真实分数，故意错误用例应失败；跨服务副本及服务重启后任务可查。真实共享工具 diff 有结果和 tool span，未授权拒绝。自检重跑报告与详情一致。提示词编辑、发布、调试与下线审计显示真实账号及环境。所有修改通过有针对性的测试与部署门禁；线上复测后更新报告，保留证据并停止测试实例。
