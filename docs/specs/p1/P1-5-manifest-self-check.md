# P1-5 ManifestValidator 与注册自检

## 目标

一份 `agent.yaml` 在写入注册表之前会被拒绝，如果名称不合法、声明的共享工具或知识库没有授权、或高风险工具没有绑定审批策略。服务起来之后，自检能报告健康、协议、追踪、审批绑定四项结果。

## 依赖

- 前置任务：P1-4
- 依赖的契约文件：`contracts/manifest.schema.json`、`contracts/invoke.openapi.yaml`、`contracts/sse-events.schema.json`、`contracts/error-codes.yaml`
- 依赖的外部组件：被测智能体的 `/v1/health`、`/v1/manifest`、`/v1/invoke`。追踪一项查 Langfuse `GET /api/public/v2/observations`（P1-1）。Langfuse 不可用时该项为失败，不要当成通过。

## 改哪些文件

```
contracts/error-codes.yaml
keel-server/src/main/java/com/keel/server/registry/service/ManifestValidator.java
keel-server/src/main/java/com/keel/server/registry/service/SelfCheckService.java
keel-server/src/test/java/com/keel/server/registry/**
docs/specs/p1/P1-5-manifest-self-check.md
```

这两个类已是空占位，在原类上实现。

## 接口契约

自检报告形状用 `SelfCheckReport`：`passed` 加上四条 `items`。

四项名称固定：

1. 健康：`GET {endpoint}/v1/health` 返回 200
2. 协议：发 5 条带 `X-Keel-Eval-Run` 的 `/v1/invoke`，SSE 事件能通过 `sse-events.schema.json`
3. 追踪：上述请求的 `trace_id` 能在 Langfuse 该环境项目里查到
4. 审批绑定：manifest 里 `risk: high` 的工具都有 `approval: required` 或对应 `approval_policy`

校验失败与自检未通过用 `error-codes.yaml` 里的新条目，先加条目再生成或手写枚举。不要为了让注册成功把缺策略的工具放行。

## 实现要点

- **先做 Schema 校验，再做业务校验。** 业务校验包括：名称唯一、owner 非空、`spec.tools` 里的共享工具在 `agent_tool_grant` 有有效授权、知识库标识非空。工具表还没由 P2-10 写入时，共享工具授权查不到就拒绝，不要默认允许。
- **自检请求必须带评测标记** `X-Keel-Eval-Run`，避免把探针算进线上调用量。
- **自检不写审计、不开通资源。** 它只返回报告。谁在注册流程里调用它是 P1-6。
- **缺审批策略是注册失败，不是警告。** 技术文档第 10 节检查清单：高风险工具未绑定审批策略则无法注册。
- 协议自检不要把用户原文写进日志。探针用固定短句。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] 缺 `approval` 的高风险工具，`ManifestValidator` 拒绝
- [ ] 未授权的共享工具，`ManifestValidator` 拒绝
- [ ] 四项自检各自有通过和失败用例；健康失败时 `passed=false`，其余项仍有结果
- [ ] 协议用例校验的是契约里的事件名，不手写一套平行格式
- [ ] 追踪用例打到假的 Langfuse HTTP，而不是 mock 掉整个 client 类

## 明确不做

- 不编排注册、不写 `agent_resource`（P1-6）
- 不实现审批策略的增删改（P3-1）。本任务只读已有 `approval_policy` 行；表还没有数据时，高风险工具视为未绑定
- 不部署智能体
