# Keel 文档

按仓库包名分类。冲突时以 `architecture/Keel-技术文档.html` 为准。

## 跨包

| 目录 | 对应范围 | 文档 |
|---|---|---|
| `design/` | 底座总体设计 | [智能体底座设计](design/Keel-智能体底座设计.html) |
| `architecture/` | 全平台实现 | [技术文档](architecture/Keel-技术文档.html) |
| `specs/` | 任务 spec | [模板](specs/_TEMPLATE.md) · [P0](specs/p0/) · [P1](specs/p1/) |
| `TASKS.md` | 任务拆解 | [TASKS](TASKS.md) |

## 按包

| 目录 | 对应仓库包 | 文档 |
|---|---|---|
| `console/` | `console/` | [控制台前端原型](console/Keel-控制台前端.html) |
| `contracts/` | `contracts/` | 契约原文在设计文档第 05 节；机器可读文件在仓库根 `contracts/` |
| `keel-gateway/` | `keel-gateway/` | 见技术文档第 04、05、08 节 |
| `keel-server/` | `keel-server/` | 见技术文档第 04、05、06、07 节 |
| `keel-audit/` | `keel-audit/` | 见技术文档第 04、05、06 节 |
| `keel-spring-boot-starter/` | `keel-spring-boot-starter/` | 见技术文档第 05、09 节 |
| `sdk-python/` | `sdk-python/` | 见技术文档第 05、09、10 节 |

各包目录在该包有独立文档时再建，不要为空目录占位。

## P0 的 spec

P0 这一期的 14 条任务全部有 spec，文件在 `specs/p0/`。当前进度和本机环境限制见 [TASKS](TASKS.md) 开头。

| 任务 | spec | 执行者 | 前置 |
|---|---|---|---|
| P0-1 | [契约 keel/v1 冻结](specs/p0/P0-1-contracts-v1.md) | Cursor | — |
| P0-1a | [运行生命周期：中断与恢复](specs/p0/P0-1a-run-lifecycle.md) | Cursor | — |
| P0-2 | [契约代码生成](specs/p0/P0-2-contract-codegen.md) | Codex | P0-1 P0-4 |
| P0-3 | [契约跨语言一致性用例](specs/p0/P0-3-contract-tests.md) | Codex | P0-1 P0-2 |
| P0-4 | [monorepo 骨架](specs/p0/P0-4-monorepo-skeleton.md)（已完成） | Codex | — |
| P0-5 | [auth-gateway 四处改造](specs/p0/P0-5-auth-gateway.md) | Cursor | — |
| P0-6 | [sdk-python 骨架](specs/p0/P0-6-sdk-python-skeleton.md) | Cursor | P0-1 P0-2 |
| P0-7 | [sdk-python 追踪](specs/p0/P0-7-sdk-python-tracing.md) | Cursor | P0-6 |
| P0-8 | [sdk-python llm / knowledge / audit](specs/p0/P0-8-sdk-python-clients.md) | Codex | P0-6 P0-7 |
| P0-9 | [keel-lite 本地审计与审批](specs/p0/P0-9-keel-lite.md) | Codex | P0-1 |
| P0-10 | [CLI new / dev](specs/p0/P0-10-cli-new-dev.md) | Codex | P0-6 P0-9 |
| P0-11 | [Java starter 协议端点](specs/p0/P0-11-java-starter.md) | Cursor | P0-2 P0-4 |
| P0-12 | [starter 追踪](specs/p0/P0-12-starter-tracing.md) | Cursor | P0-11 |
| P0-13 | [五个脚手架模板](specs/p0/P0-13-templates.md) | Codex | P0-10 P0-11 |

三条能并行的线：**契约线** P0-1/P0-1a → P0-2/P0-3（瓶颈，冻结前下游全阻塞）、**auth-gateway 线** P0-5（独立，不在本仓库）、**骨架线** P0-4（独立）。

## P1 的 spec

P1 的 21 条任务都有 spec，文件在 `specs/p1/`。已完成的是 P1-0 和 P1-15a。P1-3 只建了 V1 的三张 registry 表，其余表仍按该 spec 从 V2 补。

| 任务 | spec | 执行者 | 前置 |
|---|---|---|---|
| P1-0 | [底座最小可运行](specs/p1/P1-0-runnable-baseline.md)（已完成） | Cursor | P0-4 |
| P1-1 | [Langfuse Cloud 日本节点](specs/p1/P1-1-langfuse-deploy.md) | 人工 + Cursor | — |
| P1-2 | [自研薄网关](specs/p1/P1-2-model-gateway.md) | Cursor | — |
| P1-3 | [keel 库其余表](specs/p1/P1-3-keel-server-schema.md) | Codex | P0-4 P1-0 |
| P1-4 | [registry 查询与登记数据](specs/p1/P1-4-registry-crud.md) | Codex | P1-3 |
| P1-5 | [manifest 校验与自检](specs/p1/P1-5-manifest-self-check.md) | Cursor | P1-4 |
| P1-6 | [开通编排与回滚](specs/p1/P1-6-provisioning-rollback.md) | Cursor | P1-4 P1-5 |
| P1-7 | [OAuth 客户端与公钥](specs/p1/P1-7-auth-client-jwks.md) | Cursor | P1-6 P0-5 |
| P1-8 | [薄网关 / Langfuse / Secret](specs/p1/P1-8-external-provisioners.md) | Cursor | P1-6 P1-2 |
| P1-9 | [对账](specs/p1/P1-9-discovery-reconcile.md) | Cursor | P1-4 |
| P1-10 | [心跳与探活](specs/p1/P1-10-heartbeat-probe.md) | Codex | P1-9 |
| P1-11 | [审计哈希链](specs/p1/P1-11-audit-chain.md) | Cursor | P0-1 |
| P1-12 | [审计查询与导出](specs/p1/P1-12-audit-query-export.md) | Codex | P1-11 |
| P1-13 | [链路详情](specs/p1/P1-13-insight-traces.md) | Cursor | P1-1 P1-11 |
| P1-14 | [总览与成本](specs/p1/P1-14-insight-overview-cost.md) | Codex | P1-13 |
| P1-15 | [控制台四页接真接口](specs/p1/P1-15-console-core-pages.md) | Codex | P1-14 |
| P1-15a | [控制台七页原型](specs/p1/P1-15a-console-prototype-mock.md)（已完成） | Cursor | P1-0 |
| P1-16 | [链路列表与三视图](specs/p1/P1-16-console-traces.md) | Cursor | P1-13 P1-15 |
| P1-17 | [askdb 接入](specs/p1/P1-17-askdb-cutover.md) | Cursor | P0-6～8 |
| P1-18 | [rag-forge 接入](specs/p1/P1-18-rag-forge.md) | Codex | P1-1 P0-1 |
| P1-21 | [控制台登录与预览模式](specs/p1/P1-21-console-login.md) | Cursor | P0-5 P1-0 P1-15 |
