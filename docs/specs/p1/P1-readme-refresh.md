# P1 中英文 README 与当前实现对齐

## 目标

更新根目录中英文 README，使新读者能按当前代码、设计与部署清单理解平台边界、运行路径和开发发布方式。

## 依据

- 以远端 main 的当前实现为基线，阅读技术文档、20261014 合并设计、20261018 控制台原型及 r1020–r1023、P1 记忆相关 spec。
- 部署以 `.github/workflows/keel-cd.yml`、`deploy/k3s/services/`、`deploy/k3s/console.yaml`、沙箱清单与 Nginx 配置为依据，不把清单副本数当成在线实测。
- 核对 CLI、模板、配置和持久化实现，区分已实现、配置可选和占位能力。

## 改哪些文件

```text
README.md
README.en.md
docs/specs/p1/P1-readme-refresh.md
```

## 接口契约

不修改契约或运行行为，仅引用现有协议和命令。当前平台可写环境为 staging/prod；历史协议枚举及本地 keel dev 保持兼容。

## 界面对齐

无页面变更。仅在 README 说明已有控制台能力并链接最新原型。

## 实现要点

- 同步两种语言的结构、命令、能力边界和架构图。
- 补充分层记忆、动态模型路由、渠道、独立智能体项目与研发流程。
- 明确 gateway/starter 占位项、retire CLI 未接线、审计与账单的持久化配置条件。
- 区分 keel-system、agents、keel-devflow-sandbox，记录 meta-agent 的当前部署和本地卷限制。
- 不修改部署配置、已有业务代码或其他工作区。

## 验收标准

```bash
git diff --check
git diff --stat
```

另用临时 Python 检查两份 README 的相对文件链接、代码围栏、章节对应及共享命令/路径；对照源码复核命令。纯文档变更不运行全量业务测试。

## 明确不做

不部署服务、不发布 SDK、不修订历史设计和部署手册。完成检查后提交并推送文档更新。
