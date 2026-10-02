# Keel 文档

按仓库包名分类。冲突时以 `architecture/Keel-技术文档.html` 为准。

## 跨包

| 目录 | 对应范围 | 文档 |
|---|---|---|
| `design/` | 底座总体设计 | [智能体底座设计](design/Keel-智能体底座设计.html) |
| `architecture/` | 全平台实现 | [技术文档](architecture/Keel-技术文档.html) |
| `specs/` | 任务 spec | [模板](specs/_TEMPLATE.md) · P0 全部见下表 |
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

P0 这一期的 14 条任务全部有 spec，可以直接开工。P1 之后的 spec 在对应任务动手前再写——提前写会因为前面的实际形状变化而返工。

已提前完成的 P1 子任务：[P1-0 底座最小可运行](specs/P1-0-runnable-baseline.md)、[P1-15a 控制台七页按原型落地](specs/P1-15a-console-prototype-mock.md)。当前进度和本机环境限制见 [TASKS](TASKS.md) 开头。

| 任务 | spec | 执行者 | 前置 |
|---|---|---|---|
| P0-1 | [契约 keel/v1 冻结](specs/P0-1-contracts-v1.md) | Cursor | — |
| P0-1a | [运行生命周期：中断与恢复](specs/P0-1a-run-lifecycle.md) | Cursor | — |
| P0-2 | [契约代码生成](specs/P0-2-contract-codegen.md) | Codex | P0-1 P0-4 |
| P0-3 | [契约跨语言一致性用例](specs/P0-3-contract-tests.md) | Codex | P0-1 P0-2 |
| P0-4 | [monorepo 骨架](specs/P0-4-monorepo-skeleton.md)（已完成） | Codex | — |
| P0-5 | [auth-gateway 四处改造](specs/P0-5-auth-gateway.md) | Cursor | — |
| P0-6 | [sdk-python 骨架](specs/P0-6-sdk-python-skeleton.md) | Cursor | P0-1 P0-2 |
| P0-7 | [sdk-python 追踪](specs/P0-7-sdk-python-tracing.md) | Cursor | P0-6 |
| P0-8 | [sdk-python llm / knowledge / audit](specs/P0-8-sdk-python-clients.md) | Codex | P0-6 P0-7 |
| P0-9 | [keel-lite 本地审计与审批](specs/P0-9-keel-lite.md) | Codex | P0-1 |
| P0-10 | [CLI new / dev](specs/P0-10-cli-new-dev.md) | Codex | P0-6 P0-9 |
| P0-11 | [Java starter 协议端点](specs/P0-11-java-starter.md) | Cursor | P0-2 P0-4 |
| P0-12 | [starter 追踪](specs/P0-12-starter-tracing.md) | Cursor | P0-11 |
| P0-13 | [五个脚手架模板](specs/P0-13-templates.md) | Codex | P0-10 P0-11 |

三条能并行的线：**契约线** P0-1/P0-1a → P0-2/P0-3（瓶颈，冻结前下游全阻塞）、**auth-gateway 线** P0-5（独立，不在本仓库）、**骨架线** P0-4（独立）。
