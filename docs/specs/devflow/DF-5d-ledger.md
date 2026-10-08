# DF-5d 把确认结果写入任务账本

## 目标

人确认需求之后，如果配置了 keel-server，就以 meta-agent 的服务身份开一条研发任务，并回报 SPEC 阶段已完成。任务停在 H1，等人在控制台确认岗位说明书。

## 依赖

- 前置任务：DF-2 账本，DF-5c 确认流程。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `DevflowJobDraft`、`DevflowStageReport`。不新增字段。
- 外部组件：keel-server 已有接口。断言沿用服务身份：`X-Client-Id`、`X-Client-Assertion-Type`、`X-Client-Assertion`。受众是 `KEEL_SERVICE_AUDIENCE`。

## 改哪些文件

```
docs/specs/devflow/DF-5d-ledger.md
docs/specs/devflow/DF-5-meta-agent.md
docs/specs/devflow/README.md
sdk-python/keel/cli/ledger.py
sdk-python/tests/cli/test_ledger.py
agents/meta-agent/app.py
agents/meta-agent/tests/test_meta_agent.py
```

## 接口契约

未设置 `KEEL_SERVER_URL` 时不访问网络，确认结果里说明任务账本未写。

设置了地址但缺少 `KEEL_OAUTH_CLIENT_ID`、`KEEL_OAUTH_PRIVATE_KEY` 或 `KEEL_SERVICE_AUDIENCE` 时拒绝，不发请求。

设置齐全时：

1. `POST /api/v1/devflow/jobs`，正文 `{title, targetAgent, layer: dev, kind: CREATE, goal, template: tool-agent}`。不传 mode，由服务端把研发任务收成协作。
2. `POST /api/v1/devflow/jobs/{jobId}/stages/SPEC/report`，正文 `{status: OK, summary: 需求已确认, artifact: {kind: SPEC, ref: spec.json, origin: AGENT}}`。产物只给引用，不上传需求单正文。

服务端拒绝时，把返回的错误码原样交给调用方。

## 实现要点

- 本地 `state.json`、`spec.json`、`agent.yaml` 仍要写。恢复现场继续读本地文件。
- 客户端名必须来自环境变量。研发任务的谱系要求它是 `meta-agent`，代码里不写默认客户端名。

## 验收标准

```bash
/Users/amy/CursorProject/keel/.venv/bin/pytest sdk-python/tests/cli/test_ledger.py agents/meta-agent/tests/test_meta_agent.py -q
```

- [ ] 未配置 `KEEL_SERVER_URL` 时不发请求
- [ ] 缺断言材料时不发请求
- [ ] 请求顺序是建任务、再回报 SPEC，且请求体里没有需求单正文
- [ ] 未配置时，meta-agent 确认回复里说明任务账本未写

## 明确不做

- `keel register` / `keel release`。骨架刚生成时工具授权和镜像都还没有。
- 跑 `keel gate` 和隐藏考题。要等 H2 通过，并且员工服务已经有地址。
- 调用 `git.pr.merge`。该调用会直接让 GitHub 合并；挂在员工名下的发布审批要等员工先注册。
- 删掉本地运行现场。
