# R3-1 staging 到 prod 的发布验收

## 目标

准备一套由操作者在本机执行的脚本和验收清单：测试智能体走 staging 门禁、一次失败被拦住、一次通过后发布 staging，再发布 prod。本任务不访问线上。

## 依赖

- 前置任务：阶段 0。
- 依赖的契约文件：无新字段。`keel gate` 不通过时退出码 1，且不写 `.keel/gate-run-id`。`keel release` 把 `env`、`gateRunId`、`image` POST 到 `/api/v1/agents/{name}/releases`。
- 依赖的外部组件及其真实行为：`KEEL_SERVER_URL` 由操作者设置，脚本不写默认主机。不调用 Langfuse、薄网关或 auth-gateway 的未记载接口。

## 改哪些文件

```
docs/specs/r1014/R3-1-staging-release.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
docs/qa/r3-1/run-staging-release.sh
docs/qa/r3-1/agent.yaml
docs/qa/r3-1/app.py
docs/qa/r3-1/evals/seed.jsonl
docs/qa/r3-1/evals/seed-fail.jsonl
docs/qa/r3-1/evals/scorers.py
docs/qa/r3-1/checklist.md
docs/qa/2026-10-15-r3-1-release.md
sdk-python/tests/qa/test_r3_1_script.py
.gitignore
```

## 接口契约

- `keel register --env staging --file agent.yaml`
- `keel gate --env staging --file evals/seed-fail.jsonl --endpoint "$KEEL_AGENT_ENDPOINT"` 必须非 0 退出。
- `keel gate --env staging --file evals/seed.jsonl --endpoint "$KEEL_AGENT_ENDPOINT"` 通过后把 gateRunId 写到 `.keel/gate-run-id`。
- `keel release --env staging|prod --image "$KEEL_RELEASE_IMAGE"`
- 证据：`GET /api/v1/insight/traces?agent=qa-r3-1`，`GET /api/v1/audit/events?agent=qa-r3-1`。`KEEL_SERVER_URL` 不包含 `/api/v1`。

## 界面对齐

无。`ui-checklist.md` 没有 R3-1 行。发布记录在控制台已有页面上核对，本任务不改页面。

## 实现要点

- 脚本只在没有 `--dry-run` 时访问网络。缺 `KEEL_SERVER_URL`、`KEEL_AGENT_ENDPOINT` 或 `KEEL_RELEASE_IMAGE` 时退出码 2。
- `agent.yaml` 的 `spec.runtime.endpoint` 必须和 `KEEL_AGENT_ENDPOINT` 相同，脚本不改写 manifest。
- 失败用例的期望答案与应用返回值不同，总分低于 0.85，发布不得使用这次的门禁。
- 证据目录默认 `docs/qa/r3-1/evidence/`，不进 git。报告模板保持「待执行」，不把未跑的步骤写成通过。

## 验收标准

```bash
cd sdk-python && ../.venv/bin/python -m pytest tests/qa/test_r3_1_script.py -q
```

- [x] `--dry-run` 打印注册、失败门禁、通过门禁、staging 发布、prod 发布，且不访问网络。
- [x] 脚本不包含默认主机，也不包含密钥。
- [x] 清单要求保存失败门禁输出、通过门禁的 gateRunId、两次发布响应、trace 和审计。

## 明确不做

- 不在 keel.ragforge.net 注册、发门禁或发布。
- 不部署测试智能体，不填写真实 trace、审计或发布记录。
- 不改 `keel gate` / `keel release` 的实现。
