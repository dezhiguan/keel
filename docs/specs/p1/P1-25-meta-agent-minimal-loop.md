# P1-25 元智能体最小闭环

## 目标

按 docs/console/Keel-全流程智能体化控制台.html 的研发层需求向导、H1 需求确认、任务详情实现：DEV / CREATE / COLLAB → 真实 meta-agent 草拟 → 修改或确认 → 模板骨架 → 私有仓库 draft PR → 人工接手。完成是“骨架 PR 已交付”，不表示评测、部署或发布通过。

## 范围

contracts/console-api.openapi.yaml；keel-server application.yml 调度线程配置、devflow 模块、V20+迁移（开工时取下一个编号）、相关测试；agents/meta-agent/**；sdk-python/keel/cli/{ledger,new,repo}.py 及对应测试；services/git-ci/** 及对应测试；console/src/{api,views/agents,views/jobs,views/tools}/**；deploy 的元智能体清单和 Dockerfile、CI/CD；docs/specs/p1/P1-25*、docs/qa/*。

## 实现

- 先新增兼容的 execution / requirement / retry 契约及结构化需求可选字段，再实现三端。
- 只开放研发层新建和 Python tool-agent 骨架。业务流水线、改造、种子评测暂禁用并显示原因。服务端管理员检查，不仅依赖页面。
- 新建事务同时保存原任务和执行队列；执行状态、需求、版本、草稿、真实 PR/commit、失败信息在 PostgreSQL 保存。两个 server 副本用行锁/租约领取任务，过期可恢复，成功结果按领取版本核对。
- meta-agent 通过 SDK invoke 执行：draft/revise 返回结构化 JSON final；delivery 接受确认的草稿，只复制模板并调用 Git 服务，不执行生成代码。调用者须持共享 bridge token；配置从 Secret 注入。
- H1 使用持久化草稿及版本；confirm/revise 使用 expectedVersion 乐观锁，确认保存用户编辑；重复确认不重复交付。独立 SDK 人工挂起流程保留。
- 真实目录校验选中工具，风险不降级。最小版本不支持知识库生成。模板、预算由确定的需求覆盖模型输出，manifest 通过 schema/业务校验才展示。
- Git 建库/开 PR 可重试，已有仓库只允许带本任务标记的仓库；分步结果持久化；推送不覆盖人写 draft。令牌只在 Secret。无 Git 配置则交付失败，不伪报完成。
- 显示真实需求、manifest、PR URL、commit、trace。H1 待办从研发任务聚合进审批中心；骨架交付后停 HUMAN/BUILD。取消未启动任务或 H1；交付中的取消禁止，避免误承诺撤销已发生的 Git 写入。

## 验证和部署

- 表单、真实产物展示、待办聚合测试；Java 调度、版本/权限、异常、持久化领取测试；Python 使用真实 HTTP 假网关验证 draft/revise/delivery 与校验。
- 构建、契约、门禁通过后部署 server、console、meta-agent 和所需 Git 服务，等待 rollout。
- 浏览器真实提交 → 出草稿 → 编辑、退回 → 确认 → 真实 PR；验证重复确认和重启后状态；记录 job/trace/PR/commit 及截图。密钥不写入报告。
