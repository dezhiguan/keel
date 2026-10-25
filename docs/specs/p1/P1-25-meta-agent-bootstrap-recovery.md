# 元智能体部署准备：缺失运行 Key 恢复

## 问题与范围

Actions 40219021265 的 meta-agent 部署准备在读取 meta-agent-dev 时返回 404；线上确认该专用 Key 已不存在，keel-system/keel-meta-agent 仍保留历史 dev 运行配置。本次修复仅涉及 agents/meta-agent/ops/bootstrap_runtime.py、其测试、恢复策略 JSON、CD 中策略传送及本 spec，不迁移业务环境或平台发布版本。

## 行为

- 只有 GET 专用 Key 返回 404 且错误码为 SERVER_NOT_FOUND 时进入恢复；401、403、5xx 或其他 404 均失败，不吞掉故障。
- 缺失 Key 时必须显式提供恢复策略文件。CD 使用项目中可审阅的策略：沿用 deploy/ci/provision-meta-agent.py 的初始 10 元日预算、空 fallback、关闭降级、无 Key 级限额。只允许恢复历史 meta-agent-dev 运行凭证，不新建业务 dev 环境，不扩大到其他智能体或 prod。
- 已有 Key 沿用原预算、fallback 和限额，blocked 时拒绝修改；已有完整逻辑授权则不轮换。恢复时加上已发布 chat-default 及其具体目标。
- 先确认运行 Secret、已登记服务身份及逻辑路由，再签发；dry-run 不签发、不改 Secret。密钥通过 stdin 传送，不打印。成功签发后把新 Key 与服务身份写入运行 Secret，随后由 CD rollout 加载。
- 回归覆盖缺失、已有、幂等、blocked、非目标别名、HTTP 错误与 dry-run。原有 Key 和其他智能体资源不变；不重置网关用量。

## 验证

本地元智能体回归、PR CI/CD 门禁；合并 main 后等待 CD，核实部署准备、meta-agent rollout 和完整部署快照。
