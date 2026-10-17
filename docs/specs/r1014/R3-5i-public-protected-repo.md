# R3-5i 公有研发仓库的受保护人工评审

## 背景与目标

GitHub Free 对私有仓库的 `main` 分支保护返回 403 `Upgrade to GitHub Pro`。GitHub 官方文档确认 Free 的公有仓库支持分支保护。用户已同意在私有仓库不可用时使用公有仓库。本任务让元智能体新建的研发仓库选择公有可保护模式，并使现有 `DF-0002` 的公有仓库仍能通过精确 SHA 评审核验。

## 范围

- `services/git-ci/git_ci/github.py`：仅 `keel-devflow:{jobId}` 仓库使用可配置的 `public` / `private` 可见性；未带任务号的普通建库仍保持私有。
- `services/git-ci/git_ci/server.py` 与部署清单：`KEEL_DEVFLOW_REPO_VISIBILITY=public` 显式配置，不设置时仍为 `private`。
- 现有仓库通过人工操作改为公有前，检查所有可达提交的文件和构建日志，不公开真实密钥或隐藏考题。
- 所有生产仓库仍须核对 `keel-devflow:{jobId}` 描述标记、PR head SHA、目标 `main`、非 draft、至少一名非作者人工批准，以及 `main` 分支保护；公有可见性本身不视为审核通过。
- 分支保护 API 失败或不具备批准人时继续阻塞合并。

## 界面对齐

任务详情沿用现有 `protection`、`humanReview` 字段。公有仓库成功启用保护后显示 `ENFORCED`，不更改控制台结构。

## 验收

- 新任务建库请求携带 `private=false`，随后设置至少一名 reviewer 的分支保护；普通建库不变。
- 公有 `keel-devflow` 仓库允许交付与评审核验；标记不符、分支不符、SHA 变化、draft、零批准人仍拒绝。
- `git.pr.merge` 继续要求服务端 H4 令牌、当前 SHA 和受保护的人工审批。
- 当前 `spec-agent` 仓库变更可见性后，API 读回 `private=false`、`protection=ENFORCED`；未完成真人审批时 `approved=false`。
