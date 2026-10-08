# D0-3 委托 token 与超时恢复

## 目标

挂起超过 10 分钟后，只要建任务时留下了未过期的 consent，恢复能换成一张新的 600 秒委托 token 并继续执行。没有 consent 时仍返回 `RUN_RESUME_DENIED`。

## 依赖

- 前置任务：P3-1 的审批与挂起恢复。P3-1 禁止调用该接口，是因为当时请求体未核实；本任务以 auth-gateway 源码为准接上。
- 依赖的契约文件：`contracts/invoke.openapi.yaml` 的 `ResumeRequest`，`contracts/console-api.openapi.yaml` 的挂起与审批请求。新增字段都可选。
- 依赖的外部组件及其真实行为：2026-10-08 读 `auth-gateway` 的 `OAuthConsentController` 与 `ConsentService`。
  - `POST /oauth/delegation-token`，`application/x-www-form-urlencoded`。
  - 字段：`consent_id`、`requested_audience`、`requested_scopes`（空格分隔）、`client_id`、`client_assertion_type`（`urn:ietf:params:oauth:client-assertion-type:jwt-bearer`）、`client_assertion`。
  - 响应：`access_token`、`token_type=Bearer`、`expires_in=600`。`principal_type=agent`。
  - 客户端断言 RS256，`iss` 与 `sub` 都是 `client_id`，`exp` 距 `iat` 不超过 600 秒，`jti` 必填。
  - consent 粒度是用户 + 客户端 + scopes + 知识库，不是单次流程。默认 30 天。本任务不新建 consent 接口。

## 改哪些文件

```
docs/specs/p2/D0-3-delegation-resume.md
contracts/invoke.openapi.yaml
contracts/console-api.openapi.yaml
keel-server/src/main/resources/db/migration/V8__run_consent.sql
keel-server/src/main/java/com/keel/server/approval/**
keel-server/src/main/java/com/keel/server/integration/agent/AgentEndpointClient.java
keel-server/src/test/java/com/keel/server/approval/**
keel-server/src/test/java/com/keel/server/integration/agent/AgentEndpointClientTest.java
sdk-python/keel/auth/delegation.py
sdk-python/keel/auth/client_assertion.py
sdk-python/keel/asgi.py
sdk-python/keel/context.py
sdk-python/pyproject.toml
sdk-python/tests/auth/
```

## 接口契约

`POST /api/v1/runs` 与 `POST /api/v1/approvals` 增加可选 `consentId`。格式 `consent_` 加 1～55 位字母、数字或连字符。挂起时写入 `agent_run.consent_id`。审批单只在已有 `runId` 时把 consent 记到那次执行上，不覆盖已有值。

`ResumeRequest` 增加可选 `consent_id`。只有超过 10 分钟且库里有 consent 时，keel-server 才把它放进发给智能体的恢复请求。

智能体换票读环境变量，不写默认值：`KEEL_AUTH_GATEWAY_URL`、`KEEL_OAUTH_CLIENT_ID`、`KEEL_OAUTH_PRIVATE_KEY`、`KEEL_OAUTH_ASSERTION_AUDIENCE`、`KEEL_DELEGATION_AUDIENCE`、`KEEL_DELEGATION_SCOPES`。

## 实现要点

- 10 分钟内的恢复不换委托 token，沿用现有路径。
- 超过 10 分钟且没有 consent，仍是 `RUN_RESUME_DENIED`，不调用智能体，审批单保持 `PENDING`。
- 超过 10 分钟且有 consent，keel-server 把 `consent_id` 交给智能体。SDK 当场签客户端断言并换票。换票失败则恢复接口返回 `RUN_RESUME_DENIED`，本地运行仍是 `SUSPENDED`。
- 换到的 `access_token` 放在 `ctx.delegation_token`，有效期仍是 600 秒。能撑过几天的是 consent，不是这一张 token。
- 不在日志里打印 token 或私钥。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=ApprovalPolicyEngineTest,AgentEndpointClientTest test
cd sdk-python && ../.venv/bin/pytest tests/auth -q
```

- [ ] 没有 consent 的超时恢复仍是 `RUN_RESUME_DENIED`
- [ ] 有 consent 的超时恢复把 `consent_id` 放进智能体恢复请求
- [ ] 委托换票的表单字段与 auth-gateway 控制器一致
- [ ] 缺环境变量或网关拒绝时，SDK 不把运行标成完成

## 明确不做

- 不实现 `POST /oauth/consents` 的调用方。consent 由用户会话还在时另行创建。
- 不改 auth-gateway。
- 不把 consent 收成按单次流程授权。源码没有这个粒度。
