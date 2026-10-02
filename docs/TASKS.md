# Keel 任务拆解

来源：`docs/architecture/Keel-技术文档.html` 第 14 节里程碑 + 第 4/5/8/9 节的服务与流程拆开到可执行粒度。
每条任务对应 `docs/specs/` 下一份 spec。动手前先写 spec，不要拿着这张表直接改代码。

## 当前进度（2026-10-03）

| 状态 | 任务 |
|---|---|
| 已完成 | P0-4 monorepo 骨架；P1-0 底座最小可运行（keel-server 三个控制台接口 + console 骨架）；P1-15a 控制台七个页面按原型落地（MSW mock） |
| 已冻结 | P0-1、P0-1a 契约：评审通过，tag `contracts/v1.0.0` |
| 可以立刻开工 | P0-2（契约代码生成）；P0-9（keel-lite，只依赖 P0-1，但 canonical 哈希那条验收要等 P0-3）；P0-5（auth-gateway，独立仓库） |
| 等前置 | P0-2 完成后 P0-3 / P0-6 / P0-11 并行 → P0-7 / P0-8 / P0-10 / P0-12 → P0-13 |

已提前落地、后续任务**接着做不要重做**的部分：

- P1-3：`keel-server/src/main/resources/db/migration/V1__registry.sql` 已建 `agent`、`agent_version`、`agent_instance` 三张表。其余九张表从 `V2__` 往后加，**不要改 V1**。本地演示数据在 `db/seed-local/R__seed_agents.sql`，只在 local profile 加载
- P1-4：`GET /api/v1/agents` 已实现（列表、状态 / 关键字筛选、分页）；env、category 筛选留了 TODO
- P1-15：控制台外壳、九个菜单、总览、智能体列表已接真接口；共享服务、链路追踪、评测、工具、审批、审计、模型网关七页数据来自 `console/src/mocks/`（MSW），keel-server 实现对应接口后用 `VITE_API_MOCK=off` 切真
- 控制台契约 `console-api.openapi.yaml` 新增了 `Approval.subjectType` 等字段和 `/runs`（草案，P3-1 定稿）

## 交给其他智能体前必读：本机环境

- **本机不起 Docker**（内存受限）。依赖 Testcontainers 的测试本地自动跳过（`@Testcontainers(disabledWithoutDocker = true)`），CI 照常跑。不要为了让测试在本地跑而改用 H2（`testing.mdc` 禁止）
- 本地数据库是 Postgres.app（5432），库 `keel`、账号 `keel` / `keel` 已建。keel-server 用 `--spring.profiles.active=local` 启动
- `mvn -o` 是离线模式，**新增依赖后第一次要去掉 `-o` 联网下载**
- 跑 keel-server 用打好的 jar + `-Xmx384m`，不要常驻 `mvn spring-boot:run`
- 本地启动步骤见根目录 `README.md`「本地运行控制台」

## 派给谁：判断标准

**交给 Cursor**（你要在场、随时能打断）

- 决定后续所有代码形状的：契约、SDK 的 API 形状、包结构
- 外部系统首次对接：Langfuse v4、LiteLLM、auth-gateway 的真实行为要边调边确认
- 安全与一致性关键：哈希链、换票、配额、审批状态机、资源开通的失败回滚
- 迁移存量项目：要先读懂 askdb / careermate / offshore-wind 现有代码才能动
- 跨模块的聚合逻辑：协作图组装、对账判定

**交给 Codex**（边界清楚、能自动验证、可以并行跑）

- 有明确输入输出的机械活：Flyway 脚本、CRUD、DTO 由 schema 生成
- 照着原型或模板实现：Vue 页面、脚手架模板、K8s YAML、CI 模板
- 单一外部 API 的薄封装：LiteLlmProvisioner、SecretWriter
- 补测试、补文档

**必须人工**：买机器、部署、压测、评审。

---

## P0 契约与 SDK（第 1–2 周）

验收：`keel new hello-agent` 后 30 分钟内在本地看到追踪和审计。

| ID | 任务 | 依赖 | 执行者 | 完成标准 |
|---|---|---|---|---|
| P0-1 | `contracts/` 五份 Schema（manifest、invoke、sse-events、audit-event、trace-attributes）+ `error-codes.yaml` | — | **Cursor** | 五份文件冻结为 keel/v1，评审通过 |
| P0-1a | 运行生命周期契约：`run_id`、`suspend` 事件、`resume` 接口、`agent_run` 表定义 | — | **Cursor** | 与 P0-1 同批冻结；三种 suspend reason 的枚举一次写全 |
| P0-2 | 契约代码生成：keel-common 的 Java 模型 + Python pydantic 模型由 schema 生成 | P0-1 | Codex | 改 schema 重新生成，两边模型一致 |
| P0-3 | `contracts/tests` 跨语言一致性用例 | P0-1 | Codex | SDK 和 starter 跑同一套用例都通过 |
| P0-4 | monorepo 骨架：父 pom、各模块空工程、ArchUnit 分层规则、CI 模板 | — | Codex | `mvn -q test` 通过，ArchUnit 能拦住跨模块用 mapper |
| P0-5 | auth-gateway 四处改造：客户端注册内部 API、`audience_scopes` 表驱动、`roles` claim、注册 keel-api 受众 | — | **Cursor** | careermate、rag-forge 登录和换票回归通过 |
| P0-6 | sdk-python：`agent` / `context` / `asgi` / `protocol` 骨架 | P0-1 | **Cursor** | hello-agent 能起来，`/v1/invoke` 返回合规 SSE |
| P0-7 | sdk-python：tracing（OTel + 本地缓冲重放 + LangGraph 回调适配） | P0-6 | **Cursor** | Langfuse dev 项目能看到完整节点树，断网后能重放 |
| P0-8 | sdk-python：`llm`（→LiteLLM）、`knowledge`（→rag-forge）、audit reporter | P0-6 | Codex | 假服务集成测试通过 |
| P0-9 | keel-lite：SQLite 版审计与审批 | P0-1 | Codex | `keel dev` 下审批能挂起和批准 |
| P0-10 | CLI：`new` / `dev` | P0-6 P0-9 | Codex | 生成项目可直接 `keel dev` 起来 |
| P0-11 | Java starter：自动装配 + InvokeController(SSE) + Health/Manifest/Feedback | P0-2 | **Cursor** | 一个进程内多个 `@KeelAgent` 各自挂载成功 |
| P0-12 | starter tracing：迁入 careermate 的 TracingMdcFilter / AgentTracing，导出增加 OTLP/HTTP | P0-11 | **Cursor** | careermate 现有测试仍通过，Langfuse 能收到 |
| P0-13 | 五个脚手架模板：chat-rag / tool-agent / graph-agent / supervisor / java-spring | P0-10 | Codex | 每个模板自带 10 条冒烟用例且能跑通 |

---

## P1 平台核心（第 3–5 周）

验收：三个服务的链路串在同一个 trace 里；智能体代码里没有厂商 Key。

| ID | 任务 | 依赖 | 执行者 | 完成标准 |
|---|---|---|---|---|
| P1-1 | 观测节点 + Langfuse v4 自建，无界面初始化 dev/staging/prod 三个项目，配备份和磁盘监控 | ECS | 人工 + Cursor | 内网可访问，镜像版本已锁定 |
| P1-2 | LiteLLM 部署 + `config.yaml`（模型、价格、路由降级）+ Redis；**关闭自带 Langfuse 回调** | P1-1 | Codex | 国内模型成本不为 0，两边价格口径一致 |
| P1-0 | 底座最小可运行：keel-server 骨架 + registry 三张表 + `/me` `/insight/overview` `/agents` + console 骨架（**已完成**，见 `specs/P1-0-runnable-baseline.md`） | P0-4 | Cursor | 本地页面可访问 |
| P1-3 | keel-server 工程骨架 + `keel` 库全部 Flyway 脚本（12 张表，含 P0-1a 的 `agent_run`）。**骨架和三张 registry 表已在 P1-0 完成，只补其余九张，从 V2 开始** | P0-4 | Codex | Testcontainers 起 PG 迁移通过 |
| P1-4 | registry：agent / agent_version / agent_instance CRUD + 控制台查询接口 | P1-3 | Codex | 接口按 OpenAPI 对齐 |
| P1-5 | ManifestValidator + SelfCheckService（健康、协议、追踪、审批绑定四项自检） | P1-4 | **Cursor** | 缺授权、缺审批策略时注册被拒 |
| P1-6 | provisioning 编排 + 失败逆序回滚 + `agent_resource` 记录 | P1-4 | **Cursor** | 任一步失败后外部资源被完整回收 |
| P1-7 | AuthClientProvisioner + AgentJwksController（生成 RSA 密钥对、托管公钥） | P1-6 P0-5 | **Cursor** | 智能体能用 private_key_jwt 完成换票 |
| P1-8 | LiteLlmProvisioner / LangfuseProvisioner / SecretWriter | P1-6 | Codex | 虚拟 Key 别名为 `{agent}-{env}`，预算按汇率换算 |
| P1-9 | discovery：K8sAgentWatcher + ReconcileJob（六种 finding 判定） | P1-4 | **Cursor** | 六种场景都能产出正确 finding |
| P1-10 | discovery：HeartbeatController + DifyProber + ManifestVersionChecker | P1-9 | Codex | 心跳超 45s 置 OFFLINE |
| P1-11 | keel-audit：同步写入 + MQ 顺序消费 + 哈希链 | P0-1 | **Cursor** | 链头并发写正确，断链能检出，**100% 分支覆盖** |
| P1-12 | keel-audit：字段白名单 + PII 脱敏 + 查询 + 导出（挂审批）+ 按月分区归档 | P1-11 | Codex | 应用账号无 UPDATE/DELETE 仍能正常工作 |
| P1-13 | insight：TraceQueryService，按 traceId 取 Observations v2，组装协作图/泳道/调用树并叠加审计审批 | P1-1 P1-11 | **Cursor** | 多智能体 trace 的协作图与原型一致 |
| P1-14 | insight：OverviewService / CostService / QualityService / SharedServiceMonitor | P1-13 | Codex | 成本按智能体拆分与 LiteLLM 对得上 |
| P1-15 | console 工程骨架 + 总览、智能体、审计、模型网关四页。**页面已在 P1-0 / P1-15a 完成，剩下的是去掉 mock、接 keel-server 真接口** | P1-14 | Codex | 按原型实现，数据全部走接口 |
| P1-15a | 控制台七个页面按原型落地，数据走 MSW mock（**已完成**，见 `specs/P1-15a-console-prototype-mock.md`） | P1-0 | Cursor | `vitest run` 通过，七页可用 |
| P1-16 | console 链路追踪页：**trace 列表**（原型缺这块）+ 三视图详情 + 按智能体筛选 | P1-13 P1-15 | **Cursor** | 列表可筛选可分页，点行进详情 |
| P1-17 | askdb 接入：删 trace/audit/quota/approvals/evalstore，影子运行一周 | P0-6~8 | **Cursor** | 新旧链路数、审计条数、评测分数一致后删旧代码 |
| P1-18 | rag-forge：OTel exporter、检索分段子 span、`caller_agent`/`kb` 指标标签、`service.yaml` 登记、模型调用改走 LiteLLM | P1-1 P0-1 | Codex | 检索作为 retriever 节点进调用方的 trace；embedding 无 fallback，向量空间不变 |

---

## P2 门禁 + 网关（第 6–8 周）

验收：offshore-wind 评测不达标时发布被自动阻止；外部流量全部经过网关。

| ID | 任务 | 依赖 | 执行者 | 完成标准 |
|---|---|---|---|---|
| P2-1 | keel gate：runner / compare（按 tags 逐维度）/ report + 结论回写 | P1-1 | **Cursor** | 任一维度退步 >2pt 时退出码非 0 |
| P2-2 | keel gate 静态扫描：厂商 SDK 直连、硬编码 Key、已废弃工具 | P2-1 | Codex | 与 `.cursor/hooks/forbidden-patterns.py` 规则一致 |
| P2-3 | CI 模板（GitHub Actions / GitLab CI）：单测 → gate → 构建 → release | P2-1 | Codex | 模板生成的项目开箱可跑 |
| P2-4 | keel eval：数据集导入、`@scorer`、experiment 运行 | P1-1 | Codex | `keel eval run` 与 CI 同一套评分器 |
| P2-5 | offshore-wind 迁移：删 tracestore/evalview/admin，用例导入数据集并打 tag | P2-4 | **Cursor** | 四类用例按 tag 可逐维度对比 |
| P2-6 | keel-gateway：过滤器链骨架 + RootSpanFilter + JwtAuthFilter + AgentAccessFilter | P0-5 | **Cursor** | 被拦截的请求也有 trace |
| P2-7 | keel-gateway：QuotaFilter（Redis Lua 三维令牌桶）+ ConcurrencyLimiter | P2-6 | **Cursor** | 异常断开也释放并发位，**100% 分支覆盖** |
| P2-8 | keel-gateway：SSE 透传 + 断开取消上游 + InvokeAuditFilter + 转发 `suspend` 时释放并发位 | P2-6 | **Cursor** | 成功、失败、取消都有审计；挂起期间不占并发位 |
| P2-9 | keel-gateway：RegistryRouteLocator + RouteCache + 长轮询同步 | P2-6 P1-4 | Codex | keel-server 挂掉时继续用旧路由 |
| P2-10 | tool 模块：注册表、版本、依赖方查询、废弃任务 | P1-3 | Codex | 有 prod 依赖时下线被拒 |
| P2-11 | release 模块：发布记录、门禁结论回写、PromptDriftJob | P2-1 | Codex | production 标签漂移能告警 |
| P2-12 | console：评测中心、质量中心、共享服务三页 | P1-15 | Codex | 按原型实现 |
| P2-13 | careermate 迁移（验证 Java starter） | P0-11 P0-12 | **Cursor** | observability / RagForgeClient 本地副本删除 |
| P2-14 | ops-copilot 新建（验证 `ctx.delegate` 多智能体） | P1-17 P2-5 | **Cursor** | 并行委派串在同一 trace，预算向下传递 |

---

## P3 审批 · 研发智能体 · Dify（第 9–12 周）

验收：10 个智能体在同一个控制台管理。

| ID | 任务 | 依赖 | 执行者 |
|---|---|---|---|
| P3-1 | approval 状态机 + 超时/冷却 + 等待超 10 分钟后重新换票、委托 token 兜底；`agent_run` 的挂起与恢复（按 P0-1a） | P1-11 | **Cursor** |
| P3-2 | ApprovalNotifier（企业微信 / 钉钉 / 站内）+ 回调接口 | P3-1 | Codex |
| P3-3 | console：「工具」页 +「审批中心」（按 subject_type 分四组：工具 / 智能体 / 数据导出 / 人工介入） | P3-1 | Codex |
| P3-4 | 工具生命周期：破坏兼容强制改名、依赖方回归触发、下线拦截 | P2-10 | **Cursor** |
| P3-5 | keel-gateway 完整版：协议适配、流式脱敏、灰度、熔断 | P2-8 | **Cursor** |
| P3-6 | ai-multi-agent-dev-platform 升级（Boot 3.2→3.5、Java 17→21）并拆出 prd-agent / test-gen / dev-copilot | P2-13 | **Cursor** |
| P3-7 | code-review、ci-doctor 新建（tool-agent 模板）+ Webhook 路由 | P0-13 | Codex |
| P3-8 | Dify 接入 + cs-bot 上线 | P3-5 | **Cursor** |
| P3-9 | 压测定资源：100 并发 SSE 会话、50 QPS | P2-8 | 人工 |

---

## 并行怎么排

P0 阶段只有三条线能真正并行，其余都在等契约：

1. **契约线**：P0-1 → P0-2 / P0-3（契约冻结前，下游全部阻塞，优先做）
2. **auth-gateway 线**：P0-5 完全独立，可以第一天就开始
3. **骨架线**：P0-4 独立

P1 之后可以拉开：keel-audit（P1-11/12）、rag-forge（P1-18）、console（P1-15）三条线互不碰文件，适合丢给 Codex 并行跑。

**不要让 Cursor 和 Codex 同时改同一批文件。** 要并行就用 `git worktree` 各开一个分支，或者给 Codex 划死目录范围。冲突解起来比省下的时间多。

## 派任务给 Codex 的写法

不要贴需求描述，贴 spec 路径加验证命令：

```
按 docs/specs/P1-3-keel-server-schema.md 实现。
完成后必须 `mvn -o -pl :keel-server test` 通过。
只改 keel-server/ 目录下的文件。
```
