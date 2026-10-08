# D0-1 CLI：register / eval / gate / release

## 目标

`keel register`、`keel eval`、`keel gate`、`keel release` 能对已有的 keel-server 和 Langfuse 数据集接口跑完「注册 → 导用例 → 门禁 → 发布」。门禁不达标时退出码为 1。

## 依赖

- 前置任务：P0-10（CLI 入口）、P1-6（`POST /api/v1/agents`）、P2-16 已落地的 `POST /api/v1/gate-results` 与 `POST /api/v1/agents/{name}/releases`
- 依赖的契约文件：`contracts/console-api.openapi.yaml`、`contracts/manifest.schema.json`、`contracts/invoke.openapi.yaml`、`contracts/sse-events.schema.json`
- 依赖的外部组件及其真实行为：
  - Langfuse 数据集写入沿用 keel-server `LangfuseClient.importSeed` 已核实的两个调用：`POST /api/public/datasets` body `{name}`，`POST /api/public/dataset-items` body `{id, datasetName, input, expectedOutput}`。Basic Auth，主机只从 `LANGFUSE_HOST` 读。
  - **待确认**：用 Langfuse SDK 创建 experiment 的请求体。本任务不调用它。分数在本地按 `@scorer` 计算。

## 改哪些文件

```
docs/specs/p2/D0-1-cli-register-eval-gate-release.md
sdk-python/keel/cli/{__init__.py,server.py,register.py,release.py,eval.py,gate.py}
sdk-python/keel/eval/{dataset.py,run.py}
sdk-python/keel/gate/{compare.py,report.py,runner.py}
sdk-python/tests/cli/test_cli.py
sdk-python/tests/cli/test_delivery.py
sdk-python/tests/gate/test_compare.py
```

## 接口契约

```
keel register --env dev|test|staging|prod [--file agent.yaml]
keel eval import [--dataset 名] [--file evals/seed.jsonl]
keel eval run [--dataset 名] [--file evals/seed.jsonl] [--endpoint URL] [--scorers evals/scorers.py]
keel gate --env dev|test|staging|prod [--file ...] [--endpoint URL] [--baseline scores.json] [--scorers ...]
keel release --env staging|prod --image 镜像 [--gate-run-id ID] [--file agent.yaml]
```

地址只从环境变量读，不写默认值：`KEEL_SERVER_URL`（register / gate / release），`LANGFUSE_HOST`、`LANGFUSE_PUBLIC_KEY`、`LANGFUSE_SECRET_KEY`（eval import）。

`register` 把校验过的 `agent.yaml` 原样 POST 到 `/api/v1/agents`，并带上 `env`。服务端见到 `apiVersion` 就按 manifest 登记。

`gate` 对每条用例 POST 智能体 `/v1/invoke`，请求头 `X-Keel-Eval-Run`。字符串 input 包成 `{text}`。按 tag 取平均分，再取各 tag 的平均作为总分。总分低于 manifest `eval.gate.minScore`（缺省 0.85）则失败。`--baseline` 给出上一版各 tag 分数时，任一 tag 下降超过 `maxRegression`（0.02 按 2 分计，与 keel-server `EvalQueryService` 相同）则失败。通过后 POST `/api/v1/gate-results`，body `{agent, gateRunId, passed}`，不带 `promptVersions`（带了会对不上待发布版本，服务端会标 INVALID）。通过且回写成功才把 gateRunId 写到 `.keel/gate-run-id`。

`release` 读这个文件或 `--gate-run-id`，POST `{env, gateRunId, image}`。

## 实现要点

- 退出码：参数或配置缺失为 2；门禁不达标或服务端拒绝为 1。
- 评分函数放在 `evals/scorers.py`，签名 `(expected, actual, tags) -> float`。模板里的 `exact` 就是这一种。
- 不在 CLI 里同步 `prompts/`。哈希算法在 keel-server `PromptTexts`，这边还没对过，对不上会把版本建错。
- `retire` 仍提示尚未实现。

## 验收标准

```bash
cd sdk-python && pytest tests/cli tests/gate -q
```

- [ ] `register` 把 manifest 和 env 交到 `/api/v1/agents`
- [ ] `eval import` 只打数据集的两个已核实接口
- [ ] 任一 tag 退步超过 2 分时 `gate` 退出码为 1，且不回写
- [ ] 达标时回写 gate-results，`release` 能带上这个 gateRunId

## 明确不做

- 不创建 Langfuse experiment（请求体未核实）
- 不做 `keel gate` 的静态扫描（P2-2）
- 不改 CI 模板（P2-3）
- 不实现 `keel retire`
- 不接委托授权、Git/CI MCP、编码模型
