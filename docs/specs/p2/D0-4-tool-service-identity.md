# D0-4 工具注册表的服务身份

## 目标

已开通密钥的智能体能读工具目录。共享工具授权只能由控制台用户写入 `agent_tool_grant`，服务身份写不进去。

## 依赖

- 前置任务：P1-7 的智能体密钥与 JWKS。P2-10 的工具表。
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 增加 `POST /tools/{name}/grants`。读接口形状不变。
- 依赖的外部组件及其真实行为：客户端断言与 auth-gateway `ClientAuthenticator` 一致（2026-10-08 读过源码）。RS256，`iss` 与 `sub` 都是客户端 id，`exp` 距 `iat` 不超过 600 秒，`jti` 不可重放。断言类型 `urn:ietf:params:oauth:client-assertion-type:jwt-bearer`。验签用 keel-server 自己持有的智能体公钥，不调用 auth-gateway。

## 改哪些文件

```
docs/specs/p2/D0-4-tool-service-identity.md
contracts/console-api.openapi.yaml
docs/specs/devflow/README.md
keel-server/src/main/resources/db/migration/V9__service_assertion_jti.sql
keel-server/src/main/java/com/keel/server/auth/**
keel-server/src/main/java/com/keel/server/tool/**
keel-server/src/test/java/com/keel/server/auth/
keel-server/src/test/java/com/keel/server/tool/
```

## 接口契约

读目录（`GET /api/v1/tools`、`GET /api/v1/tools/{name}`）在控制台 Cookie 之外，接受：

```
X-Client-Id
X-Client-Assertion-Type: urn:ietf:params:oauth:client-assertion-type:jwt-bearer
X-Client-Assertion
```

`aud` 必须等于 `keel.service.audience`。该值未配置时，服务身份关闭，读接口仍只认控制台登录。

`POST /api/v1/tools/{name}/grants` 正文 `{agent, versionRange}`。`granted_by` 取当前控制台用户，不从正文读。只接受 `SHARED` 且未下线的工具。同一智能体、工具、版本范围重复提交保持 `ACTIVE`。

## 实现要点

- 服务身份只放行上面两个 GET。版本、废弃、下线、依赖方、授权写入仍要控制台登录。
- 断言带了但不合法时返回 `AUTH_UNAUTHENTICATED`，不退回控制台登录。
- `jti` 写入 `service_assertion_jti`，重复使用拒绝。过期行可以删。
- 没有任何数字员工能写 `agent_tool_grant`。主体是 `SERVICE` 或只读时返回 `AUTH_CONSOLE_FORBIDDEN`。
- 不在日志里打印断言或私钥。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=ServiceAssertionTest,ServiceIdentityFilterTest,ToolRegistryServiceTest test
```

- [ ] 合法断言可以读工具列表和详情，不需要控制台 Cookie
- [ ] 过期、受众不对、重复 jti、签名不对的断言被拒绝
- [ ] 带断言去授权写入被拒绝
- [ ] 私有工具和下线工具不能授权

## 明确不做

- 不把 Git / CI 工具登记进表（DF-4）
- 不实现 H3 审批单自动通过后写授权。本接口是批准之后的写入
- 不改 auth-gateway
