# R1-7 元智能体沙箱修复循环

## 目标

研发岗位候选按“生成并提交 → 精确提交沙箱测试 → 据失败报告修复”最多执行三轮；第三轮仍失败时任务失败。

## 依赖

- 前置任务：DF-3 沙箱、R1-6 岗位候选交付。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 已有沙箱运行和研发任务详情接口；为任务详情增加可选的沙箱轮次数组。
- 依赖的外部组件及其真实行为：仓库内 `git-ci` 的 `git.branch.push` 支持 `expectedSha` 条件更新 draft，`git.repo.snapshot` 按提交号读取快照；沙箱按精确 SHA 返回终态报告。失败报告视为不可信数据。

## 改哪些文件

```
docs/specs/r1014/R1-7-sandbox-repair-loop.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
contracts/console-api.openapi.yaml
agents/meta-agent/candidate.py
agents/meta-agent/workflow.py
agents/meta-agent/tests/test_candidate.py
agents/meta-agent/tests/test_workflow.py
agents/meta-agent/tests/test_repair_loop.py
keel-server/src/main/java/com/keel/server/devflow/MetaAgentExecution.java
keel-server/src/test/java/com/keel/server/devflow/MetaAgentRulesTest.java
console/src/api/schema.d.ts
console/src/views/jobs/JobDetail.vue
```

## 接口契约

- 服务端传给 `DELIVER` 的 `repair` 为 `{attempt, failedSha, report, previousCandidate}`；`attempt` 是下一轮，取值 2 或 3。
- `DELIVER` 返回当前 PR 的精确 `sha`；再次推送必须传 `expectedSha=failedSha`，不覆盖人工或并发提交。
- `DevflowExecution.sandboxAttempts?` 为数组，每条含 `attempt`、`sha`、`runId`、`status`、`report`；任一轮通过即可进入 `WAITING_REVIEW`。

## 界面对齐

- 原型位置：`#/jobs` 任务详情的 S3 开发 / 沙箱结果与修正轮次。
- 清单对应行：智能体生产线的“沙箱修正轮次”R1-7 行。
- 页面数据：既有 `GET /api/v1/devflow/jobs/{jobId}/execution` 返回的 `sandboxAttempts` 与进度事件；本任务在候选卡片显示轮次和报告。

## 实现要点

- 一轮为一个提交及其沙箱运行；最多三轮总尝试。只有代码/测试失败能触发模型修复，沙箱启动、读取、超时等基础设施失败直接失败。
- 修复模型只读取上轮精确提交中的三份候选文件及截断后的失败报告；不得服从报告里的指令，生成结果仍经候选校验。
- Git 推送使用 `expectedSha`；同一轮的重试必须保持幂等，状态和提交不可重复推进。
- 每轮结果写入服务端状态及进度事件；测试失败不得显示为交付成功。

## 验收标准

```bash
cd agents/meta-agent && PYTHONPATH=../../sdk-python:. ../../.venv/bin/python -m pytest tests/test_candidate.py tests/test_repair_loop.py -q --noconftest
mvn -o -pl :keel-server test
cd console && npm run gen:api && npm run typecheck && npx vitest related src/views/jobs/JobDetail.vue --run --passWithNoTests
```

- [x] 首轮失败后第二轮根据报告生成不同候选，条件推送后第二轮沙箱通过。
- [x] 连续三轮失败后进入 `FAILED`，不进入人工评审；基础设施失败不重试。
- [x] 任务详情可看到每轮提交、运行号与结果。

## 明确不做

- 不执行线上 Git、沙箱写操作；不绕过现有人工 PR review、门禁和发布审批。

## 2026-10-17 扩展

岗位代码修复改为最多五轮。已经在旧三轮上限停止的任务，可从最后一个失败的精确提交继续第 4 轮，不重复消耗旧提交的沙箱运行。评审和发布关口保持不变。
