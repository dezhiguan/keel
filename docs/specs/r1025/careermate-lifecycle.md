# CareerMate 完整生命周期接入

## 范围与决策

覆盖并取代 P2-13 的最小桥接限制。CareerMate 仍为独立项目；新增 `agent/` Python 运行时，使用底座已有 SDK（Java starter 的 LLM、知识、审计客户端目前为空占位，不能作为已实现能力使用）。保留 Java 后端、数据库、现有业务工具与前端。

底座调试 → CareerMate SDK agent → ctx.loop / ctx.llm / ctx.prompt / ctx.tools → Java 业务工具。Java 原有模型入口也通过 SDK 服务调用 ctx.llm；不得继续直连厂商。现有提示词通过 SDK 读取环境版本，Git 保留副本；托管状态下失败拒绝执行，不静默使用旧版本。原生业务工具在执行前调用底座审计，写失败拒绝执行。已有业务审计、RAGForge 与 SkyWalking 在平台无对应业务能力时保留。

## 身份与安全

SDK 入口增加桥接身份认证，使用 Secret，不允许原有硬编码 token。底座只转发其已验证的用户身份。Java 业务工具通过独立服务凭据访问，按 auth_user_id 解析本地镜像；缺身份、镜像缺失、禁用用户均拒绝，绝不回退第一个用户。平台评测不读写普通用户业务数据，写工具不提供给评测。客户端不可覆盖 agent/env。

## 生命周期

agent.yaml 声明模型、提示词、工具风险与权限、记忆、审计、评测门禁。使用平台注册开通、staging 候选、提示词同步、全量评测、发布与回滚，不再由启动 Registrar 重写正式版本。保留旧 endpoint 环境配置兼容，但仅用于首次最小注册，不覆盖已有受治理 manifest。新运行时提供 SDK 标准六端点。

## 文件范围

- 底座：本 spec、P2-13 与 ui-checklist 状态、CareerMateRegistrar 及测试、按需修复影响此链路的注册/评测实现。
- CareerMate：agent/{app.py,runtime/,tools/,prompts/,evals/,tests/,agent.yaml,requirements.lock,Dockerfile}、backend/**/keel、LLM 配置与提供器、提示词入口、工具执行审计、部署清单与 CI、接入说明。
- 使用既有 keel/v1 结构，不新增跨语言公共字段；内部服务 JSON 属 CareerMate 自有契约，记录于项目接入说明。
- 接入发现的底座缺口一并修复：SelfCheckService 对 CareerMate 探针携带已有桥接凭据；LifecycleService 的存量桥接升级刷新语言元数据；SDK 评测支持通用 KEEL_BRIDGE_TOKEN 并兼容旧元智能体变量。
- 底座首次开通种子增加 `keel-server/src/main/resources/evals/careermate.jsonl`，内容与独立项目的 10 条用例一致；使用导入器识别的 expectedOutput，避免误导入通用用例或丢失预期。
- 存量最小桥接的合成 v1 没有评测或镜像证据，会遮住新注册的 v0。VersionLifecycle 与 AgentVersionMapper 只在存在 CareerMate 完整治理候选时忽略这个已确认的合成桥接版本；真实正式版本仍优先、历史记录不修改。

## 验收

1. Python 单测覆盖身份拒绝、环境固定、工具声明收敛、评测写隔离、提示词关联和模型请求；Java 测试覆盖业务工具身份隔离、LLM DTO 转换、失败拒绝与原有回归。
2. 控制台真实调试：自我介绍、求职建议、简历/任务读取、多轮记忆、写任务与读回、异常输出。
3. Langfuse 可查根节点、generation、tool 与提示词版本；网关账单带 trace/request ID；底座可查白名单审计。
4. staging 全量评测门禁通过后发布，验证正式 manifest、镜像、提示词快照；原生 CareerMate 健康与关键 API 回归。
5. 将实际执行命令、通过数、线上证据与未完成项写入验收记录，不把 mock 当成真实模型验收。

## 线上身份兼容补充

2026-10-25 实际控制台对 careermate 返回 404，但名称检查显示已占用；登记新运行时使用 careermate-keel，不复用已移除身份。AgentChatService 与 SelfCheckService 对新标识复用 CareerMate 独立桥接凭据；首次数据集种子按新标识提供。

线上工具验收发现显示名不能作为业务主体：CareerMate 的 context.user_id 必须传已验证 ConsolePrincipal.userId；AskDB 用户名及其他智能体显示名语义保持原协议。SDK 暂停恢复须保留原调用的用户和会话上下文，不接受恢复请求覆盖身份。
