# R3-5 元智能体生产第一名研发员工

## 目标

操作者可以按清单让 meta-agent 以人机协作创建 `spec-agent`，并在走到 prod 与 7 天观察后核对目录来源和生产线「本月上线」。本任务只交付脚本、清单和未执行的报告。

## 依赖

- 前置任务：阶段 1、R2-2。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 已有 `POST /devflow/jobs`、`GET /devflow/jobs`、`GET /devflow/jobs/{jobId}/execution`、`GET /agents`。本任务不改契约。
- 依赖的外部组件及其真实行为：不调用。创建请求体里的岗位、模板和工具与 `MetaAgentExecution` 的原型清单一致。

## 改哪些文件

```
docs/specs/r1014/R3-5-first-employee.md
docs/specs/r1014/README.md
docs/qa/r3-5/job.json
docs/qa/r3-5/run-first-employee.sh
docs/qa/r3-5/checklist.md
docs/qa/2026-10-15-r3-5-first-employee.md
sdk-python/tests/qa/test_r3_5_script.py
.gitignore
```

## 接口契约

- 创建：`POST /api/v1/devflow/jobs`，`execution=true`，`layer=DEV`，`kind=CREATE`，`mode=COLLAB`，`targetAgent=spec-agent`，`template=graph-agent`，工具只有 `devflow.catalog.search` 和 `devflow.stage.report`。
- 取证只读：`GET /api/v1/devflow/jobs/{jobId}`、`GET /api/v1/devflow/jobs/{jobId}/execution`、`GET /api/v1/agents?category=dev`、`GET /api/v1/devflow/jobs`。
- 控制台会话用 Cookie。生产环境名是 `__Host-keel_console_at`，local profile 是 `keel_console_at`。

## 界面对齐

无新页面。`ui-checklist.md` 没有 R3-5 行。验收看已有的智能体目录来源和生产线「本月上线」，本任务不改页面。

## 实现要点

- 脚本没有默认主机。`create` 才提交任务；`collect` 只发 GET。两者都不调用 H4、审批决定或合并。
- `job.json` 的岗位、模板、工具和 `execution` 不允许改。日预算可以改，但必须大于等于 1。
- 证据目录 `docs/qa/r3-5/evidence/` 不进 git。报告保持「未执行」。
- 总览「本月上线」数的是看板里 `status=DONE` 的任务条数。

## 验收标准

```bash
cd sdk-python && ../.venv/bin/python -m pytest tests/qa/test_r3_5_script.py -q
```

- [x] `--dry-run` 打印创建和只读取证，且不访问网络。
- [x] 脚本不包含默认主机，也不包含密钥，也不批准 H4。
- [x] 清单要求保存任务号、目录中的 `spec-agent`、观察阶段和「本月上线」条数。

## 明确不做

- 不在 keel.ragforge.net 创建任务、确认需求、授权工具或批准发布。
- 不代替人工完成 H1、H2、H3、PR 评审和 H4。
- 不把 7 天观察写成已经通过。
