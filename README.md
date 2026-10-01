# Keel 智能体平台底座

龙骨是船底那根纵梁。船上装什么货各不相同，但每条船都靠它承重。
Keel 对智能体是同一个角色：鉴权、模型调用、知识检索、链路追踪、审计、评测、
质量监控、工具审批由底座承担，每个智能体只写自己的业务逻辑。

## 文档

文档在 `docs/` 下按包分类，索引见 [docs/README.md](docs/README.md)。

| 文件 | 内容 |
|---|---|
| `docs/architecture/Keel-技术文档.html` | 实现细节，最权威。包结构、数据库、接口、关键流程、开工前核实结论 |
| `docs/design/Keel-智能体底座设计.html` | 为什么这样设计，五份接入契约的原文 |
| `docs/console/Keel-控制台前端.html` | 控制台九个页面的交互原型，双击即可打开 |
| `docs/TASKS.md` | 任务拆解，标注每条派给 Cursor 还是 Codex |
| `docs/specs/` | 每条任务一份 spec，动手前先读 |

## 给 AI 编码助手的约束

- `AGENTS.md` — 全局铁律，所有会话都适用
- `.cursor/rules/` — 按文件类型自动挂载的分栈规范
- `.cursor/hooks.json` — 编辑后自动拦截违规写法，本轮结束跑定向测试

**用 Cursor 打开本仓库根目录**，上面这些才会生效。

## 契约

| 文件 | 作用 |
|---|---|
| `contracts/console-api.openapi.yaml` | 控制台 API，前端 / mock / 后端三方的唯一事实来源 |
| `contracts/*.schema.json` | keel/v1 智能体侧协议（待 P0-1 完成） |

验证契约：

```bash
npx @redocly/cli lint contracts/console-api.openapi.yaml
npx openapi-typescript contracts/console-api.openapi.yaml -o console/src/api/schema.d.ts
```

## 当前进度

处于 P0 起步阶段，尚无可运行代码。按 `docs/TASKS.md` 推进。
