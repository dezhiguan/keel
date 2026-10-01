# Keel 文档

按仓库包名分类。冲突时以 `architecture/Keel-技术文档.html` 为准。

## 跨包

| 目录 | 对应范围 | 文档 |
|---|---|---|
| `design/` | 底座总体设计 | [智能体底座设计](design/Keel-智能体底座设计.html) |
| `architecture/` | 全平台实现 | [技术文档](architecture/Keel-技术文档.html) |
| `specs/` | 任务 spec | [模板](specs/_TEMPLATE.md) · [P0-1 契约](specs/P0-1-contracts-v1.md) · [P0-1a 运行生命周期](specs/P0-1a-run-lifecycle.md) |
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
