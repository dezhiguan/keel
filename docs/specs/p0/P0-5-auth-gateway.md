# P0-5 auth-gateway 四处改造

## 目标

auth-gateway 能被 keel-server 程序化注册 OAuth 客户端，签发的用户 token 带 `roles` claim 且 `aud` 为 `keel-api`，换票的受众白名单由表驱动。careermate 和 rag-forge 的现有登录与换票不受影响。

## 依赖

- 前置任务：无。**完全独立，按 `TASKS.md` 的并行建议第一天就开始**
- 依赖的契约文件：无
- 依赖的外部组件及其真实行为：见 `.cursor/rules/external-apis.mdc` 的 auth-gateway 一节，下面「接口契约」是摘录

## 改哪些文件

**这些文件不在 Keel 仓库里**，在 auth-gateway 自己的仓库。本 spec 只描述改什么和验收标准，动手前先确认分支策略，不要直接改主干。

```
auth-gateway: OAuth 客户端注册的内部 API
auth-gateway: audience_scopes 表 + Flyway 脚本
auth-gateway: token 签发处加 roles claim
auth-gateway: 注册 keel-api 受众
```

## 接口契约

四处改造：

**① 客户端注册内部 API**（keel-server 的 `AuthClientProvisioner` 调用）

```
POST /internal/clients
{ client_id, jwks_uri, allowed_audiences[], scopes[], grant_types: [token-exchange] }
DELETE /internal/clients/{client_id}
```

`client_id` 用智能体名；`jwks_uri` 指向 keel-server 托管的 `GET /agents/{name}/jwks.json`。内部接口必须只在内网可达，不经过公网入口。

**② `audience_scopes` 表驱动**

换票时校验「被换 token 的 aud」和「目标 aud」都在客户端的 `allowed_audiences` 里。这张表由 keel-server 根据 manifest 的 `delegates` 自动生成，auth-gateway 只读。

**③ 用户 token 加 `roles` claim**

manifest 的 `auth.roles` 靠它做准入。**这个 claim 目前不存在**，是本任务新增的。

**④ 注册 `keel-api` 受众**

用户 token 的 `aud` 统一为 `keel-api`。网关按 roles / scopes 准入后，再换票成目标智能体的 aud。

现有行为（不改，这里只是确认）：

- Token Exchange：`POST /oauth/token-exchange`，表单字段是 **`requested_audience`、`requested_scopes`**，不是 RFC 8693 的 `audience` / `scope`
- 客户端认证只接受 private_key_jwt（RS256），从 `jwks_uri` 取公钥；assertion 有效期 ≤ 10 分钟，jti 不可重放
- 访问 token 900 秒，换票 token 600 秒
- `GET /.well-known/jwks.json`，响应缓存 1 小时

## 实现要点

- **先跑现有回归再动手**。careermate 和 rag-forge 的登录、换票都走这个服务，它们是生产中的。动任何一行代码之前，先确认现有测试能跑、能复现一次完整登录和换票，否则改完无从判断是不是弄坏了。

- **`roles` claim 是新增的，老的 token 没有**。网关的准入逻辑必须能处理「token 里没有 roles」的情况——不能直接 NPE，也不能默认放行。过渡期的行为要明确：建议缺 `roles` 时按空集合处理，即只能访问不要求 roles 的智能体。这条要和 P2-6 的 `AgentAccessFilter` 对齐。

- **`aud = keel-api` 不要写成「检查 JWT 的 aud 是否等于目标智能体」**。用户 token 不可能同时是十个智能体的 aud。这是 `external-apis.mdc` 专门点名的错误写法，review 时重点看这一处。

- **`/internal/clients` 要幂等**。P1-6 的 provisioning 编排在任一步失败时按逆序回滚，重试会再次调用注册接口。同一个 `client_id` 重复注册应该是更新而不是报错，否则回滚后重试必然失败。

- **`allowed_audiences` 的生成时机**。manifest 的 `delegates` 变了，这张表要跟着变。本任务只负责 auth-gateway 侧读这张表；写由 keel-server 在 P1-7 做。两边的字段含义要在本任务里约定清楚并写进本文件，不要留到 P1-7 再对。

- **已核实（2026-10-08，读 auth-gateway 源码）**：`POST /oauth/consents` 用用户 access token 创建 consent，粒度是用户 + 客户端 + scopes + 知识库 + 过期时间，默认 30 天，没有目标 aud，也没有单次流程号。`POST /oauth/delegation-token` 是表单：`consent_id`、`requested_audience`、`requested_scopes`、`client_id`、`client_assertion_type`、`client_assertion`。签出 600 秒的 `principal_type=agent` token。Keel 侧接入见 D0-3。

## 验收标准

```bash
# 在 auth-gateway 仓库
./mvnw test
# 回归：careermate 与 rag-forge
# 具体命令待确认，按各自仓库的集成测试入口
```

- [ ] careermate 完整登录链路回归通过
- [ ] rag-forge 登录与换票回归通过
- [ ] `POST /internal/clients` 注册一个测试客户端，用 private_key_jwt 完成一次换票
- [ ] 同一个 `client_id` 重复注册不报错（幂等）
- [ ] 签发的用户 token 里有 `roles` claim，且 `aud` 为 `keel-api`
- [ ] 目标 aud 不在 `allowed_audiences` 里时换票被拒，错误信息能看出是受众问题
- [ ] 不带 `roles` claim 的老 token 不会导致网关 500
- [ ] `/internal/clients` 从公网入口不可达

## 明确不做

- 不改 Keel 仓库里的任何文件
- 不做 keel-server 侧的 `AuthClientProvisioner` 和 `AgentJwksController`（P1-7）
- 不做网关的准入过滤器（P2-6）
- 不实现 `/oauth/consents` 的新功能，这一期只核实现状
