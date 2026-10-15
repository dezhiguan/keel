# R3-1 操作清单

在自己的终端执行。脚本不带默认主机，也不会代跑。

## 执行前

- [ ] 用 `docs/qa/r3-1/app.py` 把 `qa-r3-1` 部署到 staging，进程能对 `POST /v1/invoke` 返回 SSE。`repeat hello` 的 final 必须是 `hello`。
- [ ] 把 `agent.yaml` 的 `spec.runtime.endpoint` 改成这个已部署地址。脚本发现不一致会退出，不会改文件。
- [ ] 导出 `KEEL_SERVER_URL`（源站，不带 `/api/v1`）、`KEEL_AGENT_ENDPOINT`（与上面的 endpoint 相同）、`KEEL_RELEASE_IMAGE`（本次要发布的镜像）。
- [ ] 不要把密钥写进仓库或命令历史以外的共享记录。

## 执行

```bash
chmod +x docs/qa/r3-1/run-staging-release.sh
docs/qa/r3-1/run-staging-release.sh --dry-run
docs/qa/r3-1/run-staging-release.sh
```

- [ ] `evals/seed-fail.jsonl` 的门禁退出码不是 0，且没有生成 `.keel/gate-run-id`。输出在 `evidence/gate-fail.txt`。
- [ ] `evals/seed.jsonl` 的门禁通过。`evidence/gate-pass.txt` 里有 `passed` 为 true 的 JSON，以及 gateRunId。
- [ ] staging 发布成功。`evidence/release-staging.txt` 含 `env=staging` 和同一个 gateRunId。
- [ ] prod 发布成功。`evidence/release-prod.txt` 含 `env=prod` 和同一个 gateRunId。
- [ ] `evidence/traces.json` 能对上这次调用的 trace。打不开列表时，从控制台链路页按 `qa-r3-1` 筛选并记下 trace id。
- [ ] `evidence/audit.json` 能对上这次注册或发布的审计。打不开列表时，从控制台审计页按智能体 `qa-r3-1` 筛选并记下 event id。

## 写回报告

把上面的命令输出、gateRunId、trace id、审计 event id、两次发布响应填进 `docs/qa/2026-10-15-r3-1-release.md`。没有证据的行保持「未执行」，不要写成通过。
