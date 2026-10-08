# DF-8 隐藏考题

## 目标

H2 采纳带正文的用例时，按任务规则里的比例切出隐藏考题。只有配置好的 CI 客户端能读正文、回写按 tag 的聚合分。由智能体生产的员工发布时，必须已有批准的 `git.pr.merge` 审批单。

## 依赖

- 前置任务：DF-2 账本和 `devflow_holdout` 表，D0-1 `keel gate`。
- 依赖的契约文件：`contracts/console-api.openapi.yaml`。`seed-cases` 增加可选 `cases`。不升版本。
- 外部组件：无。不向 Langfuse 另报隐藏考题追踪。CI 里智能体自己的 generation 要不要上报，设计方案第 11 节还没定，本任务不改上报。

## 改哪些文件

```
docs/specs/devflow/DF-8-holdout.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
keel-server/src/main/resources/application.yml
keel-server/src/main/resources/db/migration/V13__devflow_holdout_result.sql
keel-server/src/main/java/com/keel/server/devflow/**
keel-server/src/main/java/com/keel/server/release/ReleaseGuard.java
keel-server/src/main/java/com/keel/server/release/ReleaseService.java
keel-server/src/test/java/com/keel/server/devflow/HoldoutServiceTest.java
keel-server/src/test/java/com/keel/server/devflow/MemoryHoldoutStore.java
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
sdk-python/keel/holdout.py
sdk-python/keel/holdout_run.py
sdk-python/keel/cli/__init__.py
sdk-python/keel/cli/gate.py
sdk-python/keel/gate/runner.py
sdk-python/tests/test_holdout.py
```

## 接口契约

`POST /devflow/jobs/{id}/seed-cases` 仍必填 `acceptedCaseIds`。可选 `cases`：`{caseId, input, expected, tags}`。只传 id 时隐藏考题条数是 0。带了正文才切分，响应仍然只有条数。

切分条数是 `floor(采纳条数 × holdoutPercent / 100)`。同一任务号切分结果稳定。比例来自研发任务规则，缺省 30。

`GET /devflow/holdout/{jobId}` 和 `POST /devflow/holdout-results` 只接受服务身份，且客户端名等于 `KEEL_CI_CLIENT_ID`。未配置该变量时两个接口都拒绝。其它调用方 `403 AUTH_CONSOLE_FORBIDDEN`。没有隐藏考题时 `404`。

聚合分每个 tag 的 `score` 在 0 到 1，`count` 大于 0。加权平均和每个 tag 都要达到 0.85（与 `keel gate` 缺省 `minScore` 相同），否则保存结果并返回 `409 DEVFLOW_HOLDOUT_FAILED`。请求体不接收单条答案。

`agent.devflow_job_id` 非空时，`POST /agents/{name}/releases` 要求该智能体有一张已批准的 `tool.call` / `git.pr.merge` 审批单，否则 `400 SERVER_INVALID_PARAM`。空的任务号不增加这道检查。

`keel gate --holdout {jobId}` 在可见用例通过后，用 CI 断言读取隐藏考题、本地评分，只提交 `{jobId, byTag}`。断言用已有的 `KEEL_CI_CLIENT_ID`、`KEEL_OAUTH_PRIVATE_KEY`、`KEEL_SERVICE_AUDIENCE`。

## 实现要点

- 切分和打分放在不访问数据库的类里。
- 再次采纳会换掉该任务已有的隐藏考题，并清掉上一次聚合分。
- 控制台的关口页、评审页、评测列表都不返回用例正文。
- 发布校验只看审批单是否已批准，不在这里调用 GitHub。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=HoldoutServiceTest test
/Users/amy/CursorProject/keel/.venv/bin/pytest sdk-python/tests/test_holdout.py
```

- [ ] 10 条用例、30% 切出 3 条，且同一任务号结果不变
- [ ] 只传 id 时隐藏考题为 0
- [ ] 非 CI、或未配置 CI 客户端时读不到正文
- [ ] 0.5 分返回 `DEVFLOW_HOLDOUT_FAILED`，0.9 分通过
- [ ] 聚合结果里没有用例原文
- [ ] 有研发任务号但没有已批准合并时，发布校验拒绝

## 明确不做

- 把聚合分塞进评测列表的 `EvalResult`（控制台在字段缺失时显示「—」）
- 决定隐藏考题的 generation 是否上报 Langfuse
- 给 CI 写一个默认客户端名
- 在发布接口里调用 GitHub
