# P1-7 OAuth 客户端开通与公钥托管

## 目标

注册一个智能体后，keel-server 生成 RSA 密钥对，用 `GET /api/v1/agents/{name}/jwks.json` 托管公钥，并在 auth-gateway 登记 OAuth 客户端。该智能体能用 private_key_jwt 换到自己的 aud。

## 依赖

- 前置任务：P1-6、P0-5
- 依赖的契约文件：`contracts/manifest.schema.json` 的 `spec.auth`、`spec.delegates`
- 依赖的外部组件：auth-gateway，行为以 `docs/specs/p0/P0-5-auth-gateway.md` 和 `.cursor/rules/external-apis.mdc` 为准

## 改哪些文件

```
keel-server/src/main/java/com/keel/server/provisioning/AuthClientProvisioner.java
keel-server/src/main/java/com/keel/server/provisioning/AgentJwksController.java
keel-server/src/main/java/com/keel/server/integration/authgw/**
keel-server/src/test/java/com/keel/server/provisioning/**
docs/specs/p1/P1-7-auth-client-jwks.md
```

## 接口契约

keel-server 对外：

```
GET /api/v1/agents/{name}/jwks.json
```

响应是标准 JWKS，至少一把 `RS256` 公钥。私钥不出现在这个响应里。

keel-server 调用 auth-gateway（P0-5）：

```
POST   /internal/clients
DELETE /internal/clients/{client_id}
```

请求体字段：`client_id`、`jwks_uri`、`allowed_audiences`、`scopes`、`grant_types: ["token-exchange"]`。

`client_id` 等于智能体名。`jwks_uri` 指向上面的公钥地址，必须是 auth-gateway 能访问的内网 URL。

换票本身不在本任务实现，但验收要用它证明客户端可用：

```
POST /oauth/token-exchange
requested_audience, requested_scopes
```

## 实现要点

- **`allowed_audiences` 由 manifest 生成，不要手写名单。** 必须同时包含「被换 token 的 aud」和「目标 aud」。普通智能体：`keel-api` 与 `spec.auth.audience`。有 `delegates` 时把每个被委派方的 audience 加进去。改 delegates 时要再次调用注册接口更新。
- **`POST /internal/clients` 按幂等使用。** 同一个 `client_id` 再注册是更新。回滚调用 `DELETE`。P0-5 若还没落地，测试只打本仓库里的假 auth-gateway，不要改 auth-gateway 主干。
- **私钥只进 Secret 的键 `KEEL_CLIENT_PRIVATE_KEY`。** 写 Secret 的动作调用 P1-6 已定义的 `SecretWriter`；本任务不实现 fabric8 写 Secret（那是 P1-8）。测试里用假 `SecretWriter` 断言私钥被交出且没有写进日志。
- **断言规则不要在这里重做。** 客户端认证是 RS256、assertion 有效期 ≤ 10 分钟、`jti` 不可重放，这是 auth-gateway 的行为。本任务只生成密钥并完成一次换票。
- 不要检查「用户 JWT 的 aud 是否等于目标智能体」。用户 token 的 aud 是 `keel-api`。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] 公钥端点返回的 `kid` 与私钥匹配，响应体里没有私钥 PEM
- [ ] 假 auth-gateway 收到的 `allowed_audiences` 含 `keel-api`、该智能体 aud，以及 manifest 里的 delegates
- [ ] 同一 `client_id` 第二次 provision 发送的是更新而不是失败
- [ ] revoke 后公钥端点不再返回该钥匙
- [ ] 用生成的私钥签一条 private_key_jwt，假 auth-gateway 能把它识别为该客户端

## 明确不做

- 不改 auth-gateway 仓库
- 不实现网关侧的换票缓存（P2-6）
- 不实现委托 token（P3-1）
- 不写薄网关虚拟 Key 和 Langfuse 数据集（P1-8）
