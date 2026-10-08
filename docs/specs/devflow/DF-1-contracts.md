# DF-1 研发任务契约

## 目标

把研发任务还没写进 `keel/v1` 的部分补上：六个 `DEVFLOW_*` 错误码，审批待办的 `source=devflow` 筛选，以及阶段回报、沙箱、隐藏考题三条路径。已经在控制台切片里写过的字段保持不变。

## 依赖

- 前置任务：无。DF-9a 已写入看板、详情、接管、批次和规则。DF-9b 已写入智能体 `layer`、`devflowJobId`。DF-9c 已写入 `devflowJobId`、`devflowGate`、关口只读和种子用例。DF-9d 已登记追踪属性 `keel.devflow.job_id`、`keel.devflow.label`。
- 依赖的契约文件：`contracts/error-codes.yaml`、`contracts/console-api.openapi.yaml`。追踪属性文件不再改。
- 外部组件：无。

## 改哪些文件

```
docs/specs/devflow/DF-1-contracts.md
docs/specs/devflow/README.md
docs/specs/devflow/DF-5-meta-agent.md
contracts/error-codes.yaml
contracts/console-api.openapi.yaml
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
sdk-python/keel/_generated/errors.py
console/src/api/schema.d.ts
agents/meta-agent/app.py
agents/meta-agent/tests/test_meta_agent.py
```

## 接口契约

新增字段和路径都是可选或新增，不升 `keel/v1`。

错误码：

| 码 | 含义 | HTTP | 可重试 |
|---|---|---|---|
| `DEVFLOW_BUDGET_EXCEEDED` | 任务花费达到预算上限 | 409 | 否 |
| `DEVFLOW_FIX_ROUNDS_EXHAUSTED` | 修复轮次用完 | 409 | 否 |
| `DEVFLOW_HOLDOUT_FAILED` | 隐藏考题未过门禁 | 409 | 否 |
| `DEVFLOW_SANDBOX_TIMEOUT` | 沙箱超过 10 分钟 | 504 | 是 |
| `DEVFLOW_GRANT_MISSING` | 缺少共享工具授权 | 403 | 否 |
| `DEVFLOW_LINEAGE_FORBIDDEN` | 不能向上生产，也不能改自己 | 403 | 否 |

`GET /approvals` 和 `GET /runs` 加可选查询参数 `source=devflow`，只返回带 `devflowJobId` 的记录。缺省仍是全部。

`GET /audit/events` 加可选查询参数 `kind`。DF-9d 已声明，yaml 里当时没写上。只在 `action=config.change` 时按 `payload.kind` 等值过滤。

`POST /devflow/jobs/{jobId}/stages/{stage}/report` 正文 `{status, summary, traceId?, costCny?, artifact?}`。`status` 为 `OK` 或 `FAILED`。产物只存引用：`kind`、`ref`、`sha256`、`origin`。调用方是研发员工的服务身份。

`POST /devflow/sandbox/runs` 正文 `{jobId, repo, ref}`，`GET /devflow/sandbox/runs/{runId}` 返回 `{runId, status, report, truncated}`。报告截断到 64KB。

`GET /devflow/holdout/{jobId}` 只给 CI 客户端，返回用例内容。`POST /devflow/holdout-results` 正文只有按 tag 的聚合分 `{jobId, byTag:[{tag, score, count}]}`，不接收单条答案。控制台和智能体接口不返回隐藏考题内容。

## 实现要点

- 错误码只经 `scripts/codegen.py` 的 `error_codes()` 生成，不跑完整 `main()`。
- 元智能体拒绝「生产业务层」和「修改自己」时改用 `DEVFLOW_LINEAGE_FORBIDDEN`。名字不合规、改造尚未支持，仍用 `SERVER_INVALID_PARAM`。
- 控制台类型由 `npm run gen:api` 从 openapi 再生成。

## 验收标准

```bash
python3 -c "import sys; sys.path.insert(0, 'scripts'); import codegen; codegen.error_codes()"
mvn -o -pl :keel-common -Dtest=GeneratedModelsTest test
/Users/amy/CursorProject/keel/.venv/bin/pytest agents/meta-agent/tests/test_meta_agent.py
```

- [ ] Java 和 Python 的 `ErrorCode` 都有这六个码，文案和 HTTP 与上表一致
- [ ] 元智能体遇到业务层或 `target_agent=meta-agent` 返回 `DEVFLOW_LINEAGE_FORBIDDEN`，且不调用模型
- [ ] openapi 含 `source`、`kind`，以及阶段回报、沙箱、隐藏考题路径

## 明确不做

- 不实现这些路径的 keel-server 行为（DF-2、DF-3、DF-8）
- 不改审计 action、挂起原因、审批 subject_type
- 不给 manifest 加层级字段
- 不实现 `keel gate --holdout` 和 `keel new --from-spec`
