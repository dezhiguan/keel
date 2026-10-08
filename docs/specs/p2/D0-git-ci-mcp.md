# D0 Git / CI MCP（GitHub）

## 目标

研发流水线能通过一个 HTTP 服务，在 GitHub 组织 `keel-agents` 里建仓库、推分支、开 PR、读 diff、评论、合并、触发 workflow、取日志。新建仓库的默认分支要求至少 1 个 approving review。

## 依赖

- 前置任务：无。工具注册表的服务身份（D0-4）和授权不在本任务。
- 依赖的契约文件：`contracts/error-codes.yaml` 新增 `GIT_UPSTREAM_FAILED`。参数不合法复用 `SERVER_INVALID_PARAM`。
- 依赖的外部组件及其真实行为：GitHub REST API `2022-11-28`。组织固定为 `keel-agents`（2026-10-08 决定放 GitHub，不放 Cursor Origin）。令牌和 API 根地址从环境变量读取，不写默认值。

## 改哪些文件

```
docs/specs/p2/D0-git-ci-mcp.md
contracts/error-codes.yaml
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
sdk-python/keel/_generated/errors.py
services/git-ci/**
```

## 接口契约

`POST /v1/tools/{name}`，JSON 正文。仓库名只允许小写字母、数字和连字符，且不能带组织前缀。服务自己拼 `keel-agents/{repo}`。

| 工具 | 正文 |
|---|---|
| `git.repo.create` | `name` |
| `git.branch.push` | `repo`，`branch`，`message`，`files[]`（`path`，`content`） |
| `git.pr.open` | `repo`，`head`，`base`，`title`，`body` |
| `git.pr.diff` | `repo`，`number` |
| `git.pr.comment` | `repo`，`number`，`body` |
| `git.pr.merge` | `repo`，`number` |
| `ci.workflow.dispatch` | `repo`，`workflow`，`ref`，可选 `inputs` |
| `ci.log.fetch` | `repo`，`runId`。日志超过 64KB 截断，响应带 `truncated` |

环境变量：`KEEL_GITHUB_TOKEN`、`KEEL_GITHUB_API`。

错误体 `{code, message, trace_id, retryable}`。上游失败用 `GIT_UPSTREAM_FAILED`。

## 实现要点

- 建库用 `POST /orgs/keel-agents/repos`（`private: true`，`auto_init: true`），然后给 `main` 设分支保护：`required_approving_review_count = 1`。
- 推分支走 Git Data API：读 `main`、建 blob 和 tree、建 commit、创建或更新 ref。
- 合并只转发给 GitHub。高风险审批仍由 keel-server 在调用本服务之前做，本服务不发审批单。
- 日志接口跟随 GitHub 的响应体，截断到 64KB。

## 验收标准

```bash
PYTHONPATH=sdk-python:services/git-ci /Users/amy/CursorProject/keel/.venv/bin/pytest services/git-ci/tests -q
```

- [ ] 建库请求打到 `keel-agents`，并随后设置分支保护
- [ ] 仓库名带斜杠或大写时拒绝，且不访问 GitHub
- [ ] 推分支、开 PR、diff、评论、合并、触发 workflow、取日志都打到对应的 GitHub 路径
- [ ] 未设置令牌或 API 地址时拒绝启动调用

## 明确不做

- 不把工具写进 keel-server 的工具注册表（D0-4 / DF-4 的登记部分）。
- 不实现 Cursor Origin。
- 不在本服务里做 `git.pr.merge` 的人工审批。
