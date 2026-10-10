# P1-21 控制台登录与预览模式

## 目标

控制台有一个只登录、不注册的登录页：账号密码或短信验证码两种方式，经 keel-server 代调 auth-gateway 完成。控制台的登录态只对 Keel 有效，和 careermate、rag-forge 以及智能体调用用的 token 互不通用。默认只有一个控制台用户「官德志」。另有预览模式：不登录也能进入控制台看全部页面，所有写操作前端置灰、后端返回 403。

## 依赖

- 前置任务：P0-5（auth-gateway 改造，已合入 auth-gateway `de30f6f`）、P1-0、P1-15
- 依赖的契约文件：`contracts/console-api.openapi.yaml`（新增 `/auth/*`，`CurrentUser` 加字段，`securitySchemes` 改为 Cookie）、`contracts/error-codes.yaml`（新增 `AUTH_*`）
- 依赖的外部组件及其真实行为（2026-10-07 读 auth-gateway 源码核实，未实测）：
  - 密码登录 `POST /auth/login/password`，表单字段 `account`、`password`、`captcha`、`challenge_id`、`target_aud`、`remember`，加客户端认证三件套 `client_id`、`client_assertion_type`、`client_assertion`。`account` 依次按手机号、邮箱、用户名匹配
  - 短信登录 `POST /auth/login/mobile`，表单字段 `phone`、`code`、`target_aud`、`remember` + 客户端认证三件套
  - 发验证码 `POST /auth/sms/send`，JSON `{phone, scene: "login", app}`。只有 `app=ragforge` 会先校验"已注册"再发
  - 图形验证码 `GET /auth/captcha` → `{captchaImage, challengeId}`。同一账号密码错 5 次后每次都要验证码（返回 423 `CAPTCHA_REQUIRED` 并附新图），错 15 次锁 15 分钟（423 `LOGIN_TEMP_LOCKED`）
  - 刷新 `POST /auth/token/refresh`（`refresh_token` + 客户端认证），登出 `POST /auth/logout`（`Authorization: Bearer`）
  - 登录、刷新都**要求客户端用 private_key_jwt 认证**，浏览器拿不到私钥，所以只能由 keel-server 代调
  - 签发时校验 `target_aud` 在客户端的 `allowed_audiences` 里；会话按 `auth_sessions.target_audience` 分开记
  - **短信登录遇到没注册的手机号会直接建用户**（`LoginService.loginMobile` 里 `createMobileUser`）。不改的话"不给注册"做不到，见下文「auth-gateway 侧改动」

## 改哪些文件

```
docs/specs/p1/P1-21-console-login.md
contracts/console-api.openapi.yaml
contracts/error-codes.yaml
keel-server/pom.xml                                         # spring-boot-starter-security、oauth2-resource-server
keel-server/src/main/java/com/keel/server/auth/**           # 新模块：登录代调、会话 Cookie、控制台用户、预览拦截
keel-server/src/main/java/com/keel/server/integration/authgw/**
keel-server/src/main/java/com/keel/server/common/MeController.java
keel-server/src/main/java/com/keel/server/insight/**        # 只改预览模式下的原文遮蔽
keel-server/src/main/resources/application.yml
keel-server/src/main/resources/application-local.yml
keel-server/src/test/java/com/keel/server/auth/**
console/src/views/login/**
console/src/stores/user.ts
console/src/api/http.ts
console/src/api/me.ts
console/src/api/auth.ts
console/src/router/index.ts
console/src/layouts/ConsoleLayout.vue
console/src/directives/write.ts
console/src/views/**/*.vue                                  # 只给写操作按钮加 v-write，不改别的
console/src/mocks/handlers.ts
console/src/mocks/data/**
```

auth-gateway 的改动不在本仓库，见下文「auth-gateway 侧改动」，在 auth-gateway 仓库单独提交。

## 接口契约

### 控制台专用 token

| 项 | 值 |
|---|---|
| 受众 aud | `keel-console`（新增）。不是 `keel-api` |
| OAuth 客户端 | `keel-console-backend`，private_key_jwt，`jwks_uri` 指向 keel-server 自己托管的公钥 |
| 客户端 `allowed_grant_types` | `["password", "mobile", "refresh_token"]`，**不给 token-exchange** |
| 客户端 `allowed_audiences` | `["keel-console"]`，只有这一个 |
| 其他客户端 | `keel-console` 不加进任何其他客户端的 `allowed_audiences`；careermate、rag-forge、keel-gateway、各智能体都拿不到这个 aud |
| keel-server 验签 | Spring Security 资源服务器 + auth-gateway JWKS，**只接受 `aud=keel-console`**；`aud=keel-api`、`careermate-api` 等一律 401 `AUTH_TOKEN_AUDIENCE` |
| keel-gateway 验签 | 仍只接受 `aud=keel-api`，所以控制台 token 也调不了智能体 |
| 浏览器保存 | keel-server 写两个 Cookie：`__Host-keel_console_at`（访问 token）、`__Host-keel_console_rt`（刷新 token）。`HttpOnly`、`Secure`、`SameSite=Strict`、`Path=/`、不设 Domain。前端 JS 读不到 token，不放 localStorage |
| 本地 http | local profile 下 Cookie 名去掉 `__Host-` 前缀、不带 `Secure`，其余相同 |
| 会话 | auth-gateway 记 `target_audience=keel-console`。控制台登出只吊销这个会话，careermate 登出不影响控制台，反之亦然 |
| 有效期 | 访问 token 仍是 900 秒；`remember` 固定 `false`，刷新 token 用默认 7 天。两个 Cookie 的 Max-Age 都跟刷新 token，访问 token 过期后浏览器仍会带上它 |

### keel-server 新增接口（全部在 `/api/v1` 下）

```
GET  /auth/options              # 公开。{methods: ["password","sms"], previewEnabled: bool}
GET  /auth/captcha              # 公开。代调 /auth/captcha → {captchaImage, challengeId}
POST /auth/sms/send             # 公开。{phone} → {sent: true, expiresIn}
POST /auth/login/password       # 公开。{account, password, captcha?, challengeId?} → CurrentUser，并写 Cookie
POST /auth/login/sms            # 公开。{phone, code} → CurrentUser，并写 Cookie
POST /auth/refresh              # 公开。读刷新 Cookie → 204，并换新 Cookie
POST /auth/logout               # 已登录。代调 /auth/logout 后清 Cookie → 204
GET  /me                        # 已登录或预览。CurrentUser
```

`CurrentUser` 新增两个可选字段：

```yaml
mode:      { type: string, enum: [USER, PREVIEW] }   # PREVIEW 时 userId 为空，displayName 为「预览访客」
readOnly:  { type: boolean }                          # PREVIEW 时恒为 true
```

### 错误码（新增到 `contracts/error-codes.yaml`）

| 码 | http | retryable | 什么时候 |
|---|---|---|---|
| `AUTH_UNAUTHENTICATED` | 401 | false | 没带 Cookie 且预览模式关闭；或访问 token 过期且刷新失败 |
| `AUTH_TOKEN_EXPIRED` | 401 | true | 访问 token 过期，前端应先调 `/auth/refresh` 再重试 |
| `AUTH_TOKEN_AUDIENCE` | 401 | false | token 不是签给 `keel-console` 的 |
| `AUTH_CONSOLE_FORBIDDEN` | 403 | false | token 有效，但用户不在控制台用户表里 |
| `AUTH_PREVIEW_READONLY` | 403 | false | 预览模式下调用写接口 |
| `AUTH_BAD_CREDENTIALS` | 401 | false | 账号或密码错、验证码错、手机号未开通控制台（三者文案相同，不暴露账号是否存在） |
| `AUTH_CAPTCHA_REQUIRED` | 423 | false | 需要图形验证码，响应 `details` 里带新图 |
| `AUTH_LOCKED` | 423 | false | 失败次数过多被临时锁定 |
| `AUTH_SMS_RATE_LIMITED` | 429 | true | 发码或校验太频繁 |
| `AUTH_GATEWAY_UNAVAILABLE` | 503 | true | auth-gateway 不可达或 5xx |

auth-gateway 的错误码只在 `integration/authgw` 里翻译成上面这些，不透传到前端。

## 实现要点

### 只登录、不注册

- 登录页没有注册入口，也没有"忘记密码"。找回密码、开通账号都找管理员。
- **auth-gateway 短信登录会自动建用户**，必须在 auth-gateway 侧拦住（下文第 ① 条）。只在 keel-server 判断不够：用户已经在 auth-gateway 建出来了。
- 发验证码时 keel-server 带 `app=keel`。手机号没开通时 auth-gateway 返回 409，keel-server **照样回 `{sent: true}`**，不发短信，也不告诉前端这个号没开通，防止用登录页探测手机号。
- 两道关：auth-gateway 校验用户有 `keel` 应用准入；keel-server 再按控制台用户表校验 `user_id`。任一关不过都不发 Cookie。

### 默认用户「官德志」

- 控制台用户表写死在 keel-server 代码里（`auth/ConsoleUsers.java`），本期只有一行：

  | 字段 | 值 |
  |---|---|
  | displayName | 官德志 |
  | platformRole | `ADMIN` |
  | org | 平台组 |
  | 账号 | `guandezhi` |
  | auth-gateway `user_id` | `TODO`：要在 auth-gateway 建好账号后回填，现在不知道 |
  | 手机号 | `TODO`：待提供；不进 git，只在 auth-gateway 里以哈希存 |

- 不建库表、不做用户管理页。以后要加第二个人时再改成表（第三次出现再抽象）。
- auth-gateway 里的账号由管理员手工建（用户名 `guandezhi`、手机号、密码哈希，并加 `user_app_membership(user_id, app='keel', role='ADMIN', status='ACTIVE')`）。**密码不进 git，也不进 Flyway 脚本**。
- `/me` 的 `userId` 用 token 的 `user_id`，`displayName`、`platformRole`、`org` 取自 `ConsoleUsers`；`roles` 取 token 的 `roles` claim。

### 本机不连 auth-gateway 时

- `keel.console.auth.mode` 取 `authgw` 或 `local`，**不写默认值**。`local` 只允许在 local profile 下用，其他 profile 配成 `local` 时启动失败。
- `local` 模式：keel-server 自己校验官德志的账号密码和短信码，口令和短信码只从环境变量 `KEEL_CONSOLE_LOCAL_PASSWORD`、`KEEL_CONSOLE_LOCAL_SMS_CODE` 读，不发短信。签 token 用启动时在内存里生成的 RSA 密钥（`iss=keel-server-local`，`aud=keel-console`），资源服务器换成这把公钥验签，其余链路（Cookie、`/me`、预览拦截）和 `authgw` 模式完全一样。
- 前端 MSW mock（`VITE_API_MOCK` 未关）下，`/auth/*` 由 mock 处理：账号 `guandezhi` + 任意非空口令、短信码 `123456` 都算成功。

### 预览模式

- **开关**：`keel.console.preview.enabled`（环境变量 `KEEL_CONSOLE_PREVIEW_ENABLED`），不写默认值。关闭时登录页不显示预览入口，未登录请求一律 401。
- **怎么算预览**：请求**完全没带**访问 Cookie 且开关打开 → 预览身份。带了 Cookie 但过期、受众不对、验签失败 → 照常 401，**不降级成预览**，否则登录过期的人会以为自己还在登录态却点不动按钮。
- **后端拦截**（`auth/PreviewReadOnlyFilter`，排在认证之后）：预览身份下只放行 `GET`、`HEAD`；其他方法一律 403 `AUTH_PREVIEW_READONLY`，不进 controller。按方法默认拒绝，不维护写接口清单，新加的写接口自动被拦。语义上是读、但用了 POST 的接口（`POST /agents/manifest-preview`、`POST /audit/verify`）同样拒绝。`POST /agents/{name}/chat` 会调模型花钱，同样拒绝。`/auth/*` 登录类接口不受这条限制。
- **GET 不许有副作用**：既然 GET 对预览开放，任何 GET 都不能写库、挪标签、调薄网关管理接口。现有 GET 如果有副作用算 bug，本任务顺带列出来，不在本任务修。
- **原文遮蔽**：预览身份是匿名的，不能看到用户原文。预览下 `GET /insight/traces/{id}` 节点的 `input` / `output` 返回空并带 `redacted: true`；审计详情的 `payload` 返回空。提示词正文、manifest、成本、指标照常返回。
- **配额**：预览请求和登录用户共用 insight 的缓存，不提供强制刷新参数。Langfuse Hobby 的 Metrics API 每天只有 100 次，预览流量不能绕过缓存。
- **前端置灰**：
  - 登录页按钮「以预览模式进入（只读）」，点了以后不调登录接口，直接进 `/overview`；是不是预览以 `/me` 返回的 `mode` 为准，不以前端标记为准。
  - 顶栏常驻黄条：「预览模式 · 只读，所有写操作已禁用」+「登录」按钮。左下角用户区显示「预览访客」。
  - 自定义指令 `v-write` 挂在每个写操作按钮上：`readOnly` 时加 `disabled`，悬停提示「预览模式不能操作，登录后可用」。写按钮有几十个，所以这里直接做成指令。
  - 写操作清单（每一个都要挂 `v-write`）：新建智能体、注册、发布、下线；提示词编辑、保存新版本、在 dev / test / staging 生效、回滚；工具注册、发新版本、废弃、下线；审批批准 / 驳回、人工介入回复；评测重跑、标记为预期变化；审计导出申请、哈希链校验；日预算调整；智能体对话。
  - `/agents/new` 在预览下重定向到 `/agents`（向导的实时预览依赖 `POST /agents/manifest-preview`，预览下用不了）。
  - `http.ts` 在 `readOnly` 时对非 GET 请求直接抛 `AUTH_PREVIEW_READONLY`，不发请求；后端仍然独立拦截，前端漏挂了 `v-write` 也写不进去。
  - 收到 403 `AUTH_PREVIEW_READONLY` 时弹提示「预览模式不能操作」，不跳登录页。
  - 顶栏环境切换、筛选、分页、打开抽屉、深链跳 Langfuse 这些不写数据的操作照常可用。

### 前端会话处理

- 路由守卫：没有 `/me` 结果时先调一次。访问 token 过期或未登录时先调 `/auth/refresh`；刷新 Cookie 还在就留在原页面。刷新失败，或预览身份但不是从预览入口进来，才跳 `/login?redirect=原路径`。登录成功后回到 `redirect`，只接受站内相对路径，防开放跳转。
- `http.ts` 收到 401 `AUTH_TOKEN_EXPIRED` 或 `AUTH_UNAUTHENTICATED` 时串行调一次 `/auth/refresh` 再重放原请求；并发的多个 401 共用同一次刷新。刷新失败跳登录页。
- 非 GET 请求带请求头 `X-Keel-Console: 1`；keel-server 对已登录用户的非 GET 请求校验这个头，缺了就 403。配合 `SameSite=Strict` 防跨站请求伪造。keel-server 不开 CORS。
- 删掉 `http.ts` 里 `TODO(P0-5): attach the auth-gateway access token (aud=keel-api)`：控制台不再带 Bearer，靠 Cookie。

### 日志与审计

- 登录成功、失败、登出、预览写请求被拒都打日志，带 `trace_id`，`agent` 字段记 `keel-console`。日志里不写口令、验证码、完整手机号（只留后 4 位）。
- 登录事件本身由 auth-gateway 的 `audit_logs` 记录，keel-audit 不重复记。预览被拒的写请求不进审计（匿名流量可以刷爆审计链）。

### auth-gateway 侧改动（在 auth-gateway 仓库做）

1. `LoginService.loginMobile`：`targetAud=keel-console` 时，手机号查不到用户直接抛 401 `BAD_CREDENTIALS`，**不调 `createMobileUser`**。
2. 新增 `enforceKeelAccess`：`targetAud=keel-console` 时要求 `user_app_membership` 里有 `app=keel` 且状态 `ACTIVE`，否则 403。密码登录、短信登录都调。
3. `SmsController`：登录场景下"先校验已注册再发码"的应用从只有 `ragforge` 扩成 `ragforge`、`keel`。
4. Flyway 新脚本：插入客户端 `keel-console-backend`（见上表）。不插用户。
5. 回归：careermate、rag-forge 的密码登录、短信登录、刷新、换票全部跑一遍。

## 验收标准

```bash
mvn -o -pl :keel-server test
cd console && npx vitest related src/views/login/LoginView.vue src/api/http.ts src/router/index.ts src/directives/write.ts src/stores/user.ts
```

- [ ] 用 `aud=keel-api` 的合法 token 调 `GET /api/v1/me` 返回 401 `AUTH_TOKEN_AUDIENCE`
- [ ] 用 careermate 登录拿到的 token（`aud=careermate-api`）调控制台任一接口返回 401
- [ ] 控制台 token（`aud=keel-console`）经 keel-gateway 调智能体返回 401
- [ ] `keel-console-backend` 客户端调 `/oauth/token-exchange` 被拒
- [ ] 未开通控制台的手机号：发码接口返回 `{sent: true}` 但没有发短信；用任意码登录返回 `AUTH_BAD_CREDENTIALS`；auth-gateway `auth_users` 里**没有**新增行
- [ ] 不在 `ConsoleUsers` 里的 auth-gateway 用户（有 `keel` 准入）登录返回 403 `AUTH_CONSOLE_FORBIDDEN`，不写 Cookie
- [ ] 官德志用账号密码、短信两种方式都能登录，`/me` 返回 `displayName=官德志`、`platformRole=ADMIN`、`mode=USER`
- [ ] 登录响应的 Set-Cookie 带 `HttpOnly`、`Secure`、`SameSite=Strict`，响应体里没有 token
- [ ] 控制台登出后 careermate 的会话仍有效
- [ ] 预览开关打开、不带 Cookie：九个页面的 GET 全部 200；对 `console-api.openapi.yaml` 里**每一个**非 GET 接口发请求都返回 403 `AUTH_PREVIEW_READONLY`（用例从契约文件遍历生成，不手写清单）
- [ ] 预览下链路节点没有 `input` / `output`，审计详情没有 `payload`
- [ ] 带过期 Cookie 且预览开关打开：返回 401，不是预览数据
- [ ] 预览开关关闭、不带 Cookie：返回 401 `AUTH_UNAUTHENTICATED`，登录页不显示预览入口
- [ ] `keel.console.auth.mode=local` 在非 local profile 下启动失败
- [ ] 前端：预览模式下上面「写操作清单」里的每个按钮都是 `disabled`；`http.ts` 在预览下不发出非 GET 请求
- [ ] 前端：`/login?redirect=https://evil.example` 登录后跳 `/overview`，不跳外站

## 明确不做

- 不做注册、找回密码、改密码、绑定手机号、多因素认证
- 不做用户管理页和角色分配；第二个控制台用户出现前不建用户表
- 不做按角色的菜单和按钮控制（`utils/permission.ts` 的 TODO 留给 P1-15）；本期官德志是 ADMIN，预览是只读，只有这两种
- 不接 Langfuse 单点登录；auth-gateway 仍不是 OIDC 提供方
- 不做 keel-gateway 的任何改动（它本来就只认 `aud=keel-api`）
- 不修现有 GET 接口的副作用，只列出来
