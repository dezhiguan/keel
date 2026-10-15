# R3-2 高风险挂起后恢复

## 目标

准备由操作者执行的脚本和验收清单：高风险工具挂起、超过 10 分钟后批准、恢复时重新换委托 token。本任务不访问线上。

## 依赖

- 前置任务：无。
- 依赖的契约文件：无新字段。挂起事件带 `run_id`、`resume_token`、`ref`（审批单号）和 `trace_id`。恢复请求里 `decision` 必须是 `approve`。审批接口的决定值是 `APPROVE`。
- 依赖的外部组件及其真实行为：委托换票按 `.cursor/rules/external-apis.mdc`，`POST /oauth/delegation-token`，签发的 token 为 600 秒。恢复请求带 `consent_id` 时，智能体进程调用 `issue_from_environment` 重新换票。脚本不调用 auth-gateway。

## 改哪些文件

```
docs/specs/r1014/R3-2-approval-resume.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
docs/qa/r3-2/run-approval-resume.sh
docs/qa/r3-2/agent.yaml
docs/qa/r3-2/app.py
docs/qa/r3-2/checklist.md
docs/qa/2026-10-15-r3-2-approval.md
sdk-python/tests/qa/test_r3_2_script.py
.gitignore
```

## 接口契约

- 调用 `POST $KEEL_AGENT_ENDPOINT/v1/invoke`，body `{"input":{"text":"export"}}`。
- 批准 `POST $KEEL_SERVER_URL/api/v1/approvals/{id}/decision`，body `{"decision":"APPROVE"}`。
- 恢复 `POST $KEEL_AGENT_ENDPOINT/v1/runs/{run_id}/resume`，body 含 `resume_token`、`decision=approve`、`consent_id`。
- 等待秒数来自 `R3_WAIT_SECONDS`，缺省 660，小于 600 则退出。

## 界面对齐

无。`ui-checklist.md` 没有 R3-2 行。审批在已有的待我处理页完成，本任务不改页面。

## 实现要点

- 三段审计以现有写入为准：`run.suspend`、批准后的 `approval`、恢复后的 `tool.call` 且 `decision=allowed`。同一 `run_id` 和 `trace_id`。代码没有另写 `run.resume` 审计时，报告照实记录，不补事件。
- 换票发生在智能体进程里。脚本只把已有的 `KEEL_CONSENT_ID` 放进恢复请求，不读取私钥。
- `--dry-run` 不睡眠、不访问网络。

## 验收标准

```bash
cd sdk-python && ../.venv/bin/python -m pytest tests/qa/test_r3_2_script.py -q
```

- [x] `--dry-run` 打印调用、至少 600 秒等待、批准和带 `consent_id` 的恢复。
- [x] 未设置 `KEEL_SERVER_URL` 时退出码 2。
- [x] 清单要求保存挂起、审批、恢复的审计和同一条链路。

## 明确不做

- 不在线上调用智能体、批准或换票。
- 不创建 consent，不把私钥写进脚本。
- 不改挂起和换票实现。
