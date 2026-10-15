# R2-3 交付仓库的工作流权限

## 目标

Git 服务在新建或确认交付仓库时打开 GitHub Actions，并把默认工作流权限设为只读。交付提交包含 `.github/workflows/keel.yml`，首次推送即可触发 CI。令牌没有 workflow 权限时明确失败，不再改放到 `ci/keel.yml`。

## 依赖

- 前置任务：无。交付用的工作流内容来自 R2-2 的模板，本任务不改模板正文。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `DevflowExecution.delivery.checks`。新增枚举值 `ENABLED`，保留 `MANUAL`。
- 依赖的外部组件及其真实行为：GitHub REST `2022-11-28`。`PUT /repos/{owner}/{repo}/actions/permissions` 打开 Actions；`PUT /repos/{owner}/{repo}/actions/permissions/workflow` 设置 `default_workflow_permissions`。Git Data API 创建含 `.github/workflows` 的 tree 时，令牌必须带 workflow 权限，否则 GitHub 返回 404。本任务不调用真实 GitHub。

## 改哪些文件

```
docs/specs/r1014/R2-3-workflow-permission.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/jobs/JobDetail.vue
services/git-ci/git_ci/github.py
services/git-ci/tests/test_tools.py
agents/meta-agent/workflow.py
agents/meta-agent/tests/test_workflow.py
```

## 接口契约

- 建库和确认已有交付仓库后调用：
  - `PUT /repos/keel-agents/{name}/actions/permissions`，body `{"enabled": true, "allowed_actions": "all"}`
  - `PUT /repos/keel-agents/{name}/actions/permissions/workflow`，body `{"default_workflow_permissions": "read", "can_approve_pull_request_reviews": false}`
- 推送文件里路径以 `.github/workflows/` 开头时，仍走现有 Git Data API。若创建 tree 返回 404，服务返回 403，说明令牌缺少 workflow 权限。
- `delivery.checks`：`ENABLED` 表示工作流已在 `.github/workflows/keel.yml`；`MANUAL` 只留给旧记录。

## 界面对齐

- 原型位置：`#/jobs` 任务详情里的交付卡片。原型没有这句横幅；现有页面在 `checks === MANUAL` 时警告工作流未安装。
- 对齐清单：没有 R2-3 行。
- 页面数据：任务执行记录里的 `delivery.checks`，不新增接口。

## 实现要点

- 先打开 Actions，再推含工作流的提交，这样首次推送能匹配 `on: push`。
- 元智能体不再删除模板里的 `.github/workflows/keel.yml`，也不再写 `ci/keel.yml`。
- 工作流装上只表示 CI 会触发。不把它记成测试、门禁或发布已经通过。
- 默认工作流权限用 `read`。模板里的发布步骤使用单独的令牌 secret，不靠 Actions 默认令牌写仓库。

## 验收标准

```bash
cd services/git-ci && PYTHONPATH=../../sdk-python:. ../../.venv/bin/python -m pytest tests/test_tools.py -q
cd agents/meta-agent && PYTHONPATH=../../sdk-python:. ../../.venv/bin/python -m pytest tests/test_workflow.py tests/test_candidate.py tests/test_prompt_delivery.py -q
cd console && npm run gen:api && npm run typecheck && npx vitest related src/views/jobs/JobDetail.vue --run --passWithNoTests
```

- [x] 建库请求之后有打开 Actions 和设置只读工作流权限的两次 PUT。
- [x] 含 `.github/workflows` 的 tree 若被 GitHub 以 404 拒绝，错误说明缺少 workflow 权限。
- [x] 交付文件包含 `.github/workflows/keel.yml`，`checks` 为 `ENABLED`，没有 `ci/keel.yml`。

## 明确不做

- 不更换线上 GitHub 令牌，也不在 keel.ragforge.net 上创建仓库。
- 不把 Actions 默认权限改成 write。
- 不改模板工作流里的测试、门禁和发布步骤。
