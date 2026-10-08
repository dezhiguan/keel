# DF-5c 建仓库并开 PR

## 目标

需求确认并生成骨架之后，通过已有的 Git 服务在 `keel-agents` 建私有仓库、把骨架推到 `draft`、对 `main` 开 PR。不合并。分支保护仍由 Git 服务在建库时要求至少 1 个 approving review。

## 依赖

- 前置任务：DF-5b 骨架，D0 的 Git 服务。
- 依赖的契约文件：无新字段。工具正文沿用 `docs/specs/p2/D0-git-ci-mcp.md`。
- 外部组件：只调用本仓库的 Git 服务 `POST /v1/tools/{name}`。地址来自 `KEEL_GIT_CI_URL`，不写默认值。令牌仍只在 Git 服务里。

## 改哪些文件

```
docs/specs/devflow/DF-5c-repo.md
docs/specs/devflow/DF-5-meta-agent.md
docs/specs/devflow/README.md
sdk-python/keel/cli/repo.py
sdk-python/tests/cli/test_repo.py
agents/meta-agent/app.py
agents/meta-agent/tests/test_meta_agent.py
```

## 接口契约

未设置 `KEEL_GIT_CI_URL` 时不访问网络，确认结果里说明仓库未建。

设置之后按顺序调用：

| 工具 | 正文 |
|---|---|
| `git.repo.create` | `name` = manifest 的 `metadata.name` |
| `git.branch.push` | `repo`，`branch=draft`，`message` = 需求单标题，`files` 为骨架里的文本文件 |
| `git.pr.open` | `repo`，`head=draft`，`base=main`，`title` = 标题，`body` = 需求单目标 |

不调用 `git.pr.merge`。上游返回 4xx 或 5xx 时，确认以 `GIT_UPSTREAM_FAILED` 结束。

## 实现要点

- 跳过 `.git`、`.keel`、`__pycache__`、`.pytest_cache`。读不成 UTF-8 的文件跳过。
- 路径里不允许 `..`。
- meta-agent 进程不执行骨架里的代码。

## 验收标准

```bash
/Users/amy/CursorProject/keel/.venv/bin/pytest sdk-python/tests/cli/test_repo.py agents/meta-agent/tests/test_meta_agent.py -q
```

- [ ] 未配置地址时不发请求
- [ ] 三次调用的路径和分支正确，且没有 merge
- [ ] 推送正文里有已确认的 `agent.yaml`
- [ ] 未配置时，meta-agent 确认回复里说明仓库未建

## 明确不做

- 合并 PR，以及为此创建发布审批（DF-5d）
- 发现人写的提交后挂起问人
- 给 meta-agent 自己的仓库写权限
- 在智能体进程里保存 GitHub 令牌
