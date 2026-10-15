# R3-2 操作清单

在自己的终端执行。脚本不带默认主机，也不读取私钥。

## 执行前

- [ ] 用 `docs/qa/r3-2/app.py` 部署 `qa-r3-2`。进程的 `KEEL_AUDIT_URL` 指向这次批准会写到的控制面，否则恢复时查不到已批准的单。
- [ ] 委托换票所需的 `KEEL_AUTH_GATEWAY_URL`、客户端断言和受众配置在智能体进程里，不写进本脚本。
- [ ] 用已登录用户的 access token 调 `POST /oauth/consents` 建好 consent。导出 `KEEL_CONSENT_ID`。
- [ ] 导出 `KEEL_SERVER_URL`（源站，不带 `/api/v1`）和 `KEEL_AGENT_ENDPOINT`（已部署地址，不带路径）。
- [ ] `R3_WAIT_SECONDS` 缺省 660。不要设成小于 600，否则脚本拒绝执行。

## 执行

```bash
chmod +x docs/qa/r3-2/run-approval-resume.sh
docs/qa/r3-2/run-approval-resume.sh --dry-run
docs/qa/r3-2/run-approval-resume.sh
```

脚本会睡眠至少 600 秒，然后才批准。

- [ ] `evidence/suspend.json` 有 `run_id`、`resume_token`、`ref`、`trace_id`。
- [ ] `evidence/approval.json` 的决定是批准。
- [ ] `evidence/resume.sse` 的 final 是 `exported:export`。恢复请求带了 `consent_id`，由智能体进程重新换 600 秒委托 token。
- [ ] `evidence/audit.json` 里同一 `run_id` 能看到挂起（`run.suspend`）、审批（`approval`）和恢复后的工具调用（`tool.call`，`decision` 为 `allowed`）。没有单独的 `run.resume` 审计时，报告照实写。
- [ ] `evidence/traces.json` 或控制台链路页能打开上面的 `trace_id`，同一条链路里能看到挂起和恢复。

## 写回报告

把证据填进 `docs/qa/2026-10-15-r3-2-approval.md`。没跑到的行保持「未执行」。
