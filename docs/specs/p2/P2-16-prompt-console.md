# P2-16 提示词：keel-server 管理、发布与控制台页面

## 目标

业务方在控制台"提示词"页改智能体的提示词：保存成新版本，先在 dev / test 试，再在 staging 生效并自动回归；`keel release` 过门禁后 production 标签挪到同一版。代码里改了 `prompts/` 文件，注册或 CI 时自动带成新版本并在控制台标出来。prod 可以回滚；有人绕过 Keel 挪了 staging 或 production 标签会告警。页面跟随顶栏的四个环境切换。

## 依赖

- 前置任务：P2-15（manifest `prompts.items`、generation 上的提示词属性）、P2-1（keel gate 回写）、P2-11（release 模块）、P1-15（控制台骨架）、P1-20（环境切换）
- 依赖的契约文件：`contracts/console-api.openapi.yaml`（新增 `/prompts` 相关路径）、`contracts/error-codes.yaml`、`contracts/audit-event.schema.json`（只用已有的 `config.change`，不加取值）
- 依赖的外部组件及其真实行为（2026-10-07 按官方文档核实，未实测）：
  - 四个环境共用一个 Langfuse 项目（2026-10-07 定），keel-server 用现有的那对项目 Key。
  - `GET /api/public/v2/prompts`：列表，返回每个提示词的 versions、labels，不含内容；支持 name、label、page、limit。
  - `GET /api/public/v2/prompts/{name}?version=`：读某一版原文。
  - `POST /api/public/v2/prompts`：同名追加版本；type 建了不能改。**config、commitMessage 在请求里怎么传未核实，开工时先查 API 参考并实测。**
  - `PATCH /api/public/v2/prompts/{name}/versions/{version}`，body `{"newLabels":[...]}`：一个标签同时只在一个版本上。**`newLabels` 是覆盖还是追加未核实**：挪一个标签时要先读出该版本已有的标签再一起传，开工时实测。
  - 保护标签在 Hobby / Core 都没有；Hobby 网页用户只有 2 个。

## 改哪些文件

```
contracts/console-api.openapi.yaml
contracts/error-codes.yaml
keel-server/src/main/java/com/keel/server/prompt/**
keel-server/src/main/java/com/keel/server/release/ReleaseService.java
keel-server/src/main/java/com/keel/server/release/GateResultController.java
keel-server/src/main/java/com/keel/server/release/PromptDriftJob.java
keel-server/src/main/java/com/keel/server/provisioning/LangfuseProvisioner.java
keel-server/src/main/java/com/keel/server/integration/langfuse/**
keel-server/src/main/resources/db/migration/V7__prompt_promotion.sql
keel-server/src/test/java/com/keel/server/prompt/**
keel-server/src/test/java/com/keel/server/release/**
sdk-python/keel/cli/register.py
templates/*/.github/workflows/keel.yml
console/src/api/prompts.ts
console/src/views/prompts/**
console/src/router/index.ts
console/src/router/nav.ts
console/src/views/trace/TraceDetail.vue
console/src/**/*prompt*.test.ts
docs/specs/p2/P2-16-prompt-console.md
```

## 接口契约

标签：dev → `dev`，test → `test`，staging → `staging`，prod → `production`。

console API（加进 `contracts/console-api.openapi.yaml`）：

```
GET  /prompts?env=all|dev|test|staging|prod&agent
     -> [{ agent, name, type,
           envs: { dev: {version}, test: {version}, staging: {version, gate}, prod: {version} },  # env=all 时四个都给，单一环境只给那一个
           gate: pending|passed|failed|invalid|none,
           drift: [staging|prod],
           codeVersion: {version, gitSha} | null }]        # 代码里带上来、还没在任何环境生效的版本
GET  /agents/{name}/prompts/{prompt}
     -> { versions: [{version, labels[], commitMessage, source: console|code, createdAt, createdBy}] }
GET  /agents/{name}/prompts/{prompt}/versions/{version}
     -> { version, type, prompt, config, variables[] }
GET  /agents/{name}/prompts/{prompt}/diff?from&to
     -> { lines: [{op: same|add|del, text}], added, removed }
POST /agents/{name}/prompts/{prompt}/versions   { prompt, config?, commitMessage, source?, gitSha? }
POST /agents/{name}/prompts/{prompt}/promote    { env: dev|test|staging, version }
POST /agents/{name}/prompts/{prompt}/rollback   { version, reason }        # 只针对 production
POST /agents/{name}/prompts/sync                { files: [{name, type, sha256, prompt}], gitSha }   # keel register / CI 调
```

新表 `prompt_promotion`（V7），只记 staging：

```
id, agent_name, prompt_name, version, sha256, promoted_by, promoted_at,
gate_run_id (nullable), gate_status (PENDING/PASSED/FAILED/INVALID)
```

`release_record.prompt_versions_json` 形如 `{"answer":{"version":13,"sha256":"…"}}`。

`POST /api/v1/gate-results` 增加可选字段 `promptVersions: {"answer": 13}`：keel gate 从实验里 generation 的 `langfuse.observation.prompt.version` 读出来回写。

错误码（登记到 `contracts/error-codes.yaml`，和 P2-15 同属 `PROMPT_` 前缀）：`PROMPT_NOT_GATED`、`PROMPT_ROLLBACK_TARGET`。

## 实现要点

- **生效只挪标签，不复制内容。** 四个环境共用一个项目，版本号全局一套。`promote` 的 env 不接受 `prod`；production 标签只有 release 和 rollback 两条路径。
- **dev、test 随时挪，只写审计。** staging：挪标签 → 写一行 `prompt_promotion`（PENDING）→ 触发该智能体的回归实验。触发方式和"工具改描述触发依赖方回归"用同一个 CI 接口。TODO：P1-19 / P2-3 还没定 keel-server 调哪个 CI 接口（GitHub `workflow_dispatch` 还是 GitLab pipeline trigger），定了再接；接之前 promote 只记 PENDING，控制台提示"请手动运行 keel gate"。
- **门禁回写时比对版本。** 实验里只要有一条 generation 的提示词版本不是 PENDING 那一版，标 INVALID，不算通过。staging 缓存 60 秒，刚挪完标签的调用会命中旧版本。
- **release。** 每个声明的提示词：staging 标签所在版本必须等于最近一条 PASSED 的 promotion，否则拒绝发布，返回 `PROMPT_NOT_GATED`，不发任何 PATCH。通过后把 production 标签挪到同一版，版本号和 sha256 写进 `release_record.prompt_versions_json`。多个提示词中途失败，要把已挪的 production 标签挪回原版本，再整体失败。
- **回滚只能挪到 `release_record` 里出现过的版本**，否则 `PROMPT_ROLLBACK_TARGET`。不重跑门禁；写一条 `release_record`（标记 rollback）和审计。
- **代码同步（`/prompts/sync`）。** `keel register` 和 CI 把 `prompts/` 每个文件的内容和 sha256 交上来：
  - manifest 新声明、Langfuse 里还没有的：建第一版，打 `dev`、`test`、`staging` 标签（不打 production），再写一行 PENDING 的 promotion 并触发回归。
  - 已有的：按 sha256 在该提示词所有版本里查重，找到就什么都不做；找不到就建新版本，`commitMessage` 为 `from git {sha}`，不挪任何标签。控制台把它显示为"代码里有新版本，未生效"。
  - 重复调用必须幂等，CI 重跑不能多建版本。
  - manifest 里删掉了的提示词不删 Langfuse 里的版本，控制台标"已不在 manifest 中"。
- **漂移检查。** `PromptDriftJob` 每 10 分钟查：production 标签版本 ≠ 最近一条 `release_record`；staging 标签版本 ≠ 最近一条 `prompt_promotion`。命中就告警，写 `config.change` 审计（`payload.kind=drift`），不自动改回。dev、test 不查。用列表接口按 label 过滤，注意别超限流（数值待确认）。
- **保存必须带提交说明。** config 只留 `temperature`、`max_tokens`、`top_p`，写了 `model` 返回 400。名字必须是 `{agent}/{name}` 且在 manifest `prompts.items` 里声明，未声明返回 `PROMPT_NOT_DECLARED`；type 与声明不一致拒绝保存。
- **审计。** 保存、代码同步建版本、promote、release、回滚都写 `config.change`，`resource` 为 `prompt:{agent}/{name}`，`env` 写被挪标签的那个环境（新建版本写 `staging`），payload 只放版本号、sha256、改动行数、gate_run_id、gitSha，不放原文。
- **权限。** 智能体负责人只能改本组织智能体的提示词；平台管理员都能改；后端再校验一次。
- **diff 在服务端算。** 前端不拉两份全文自己算。chat 类型按消息逐条比对。
- **控制台页面跟随顶栏环境。** 全部环境：一行显示四个环境各自的版本。选中 dev / test / staging：只显示该环境的版本，详情里能把该环境挪到任意版本。选中 prod：只读，只有回滚。列表能按"回归中 / 回归未过 / 待发布 / 代码里有新版本 / 漂移 / 一致 / 未发布"筛选。链路详情的 generation 节点显示"提示词 offshore-wind/answer v13"并链到这个页面，`keel.prompt.fallback=true` 时标黄。原型见 `docs/console/Keel-控制台前端.html` 的"提示词"页。
- 提示词写操作放在 `prompt/` 包，不放 `insight/`。Langfuse 调用只经 `integration/langfuse`。测试用假的 Langfuse HTTP 服务，不要 mock 整个 client 类。

## 验收标准

```bash
mvn -o -pl :keel-server test
cd console && npx vue-tsc --noEmit && npx vitest related src/views/prompts src/api/prompts.ts
```

- [ ] 保存新版本只发 `POST /api/public/v2/prompts`，不发 PATCH；promote env=prod 返回 400
- [ ] promote dev / test 只有一次 PATCH 和一条审计，没有 `prompt_promotion` 行
- [ ] promote staging 后有一行 PENDING；门禁回写的版本不一致时变 INVALID
- [ ] staging 版本没有 PASSED 的 promotion 时，release 返回 `PROMPT_NOT_GATED`，假 Langfuse 没收到任何 PATCH
- [ ] release 通过时 production 标签挪到和 staging 同一版，没有 POST
- [ ] 同步中途失败，已挪的 production 标签被挪回
- [ ] 回滚到未发布过的版本返回 `PROMPT_ROLLBACK_TARGET`
- [ ] 同一组文件调两次 `/prompts/sync`，只建一次版本；内容没变时一次都不建
- [ ] 假 Langfuse 里手动挪了 staging 或 production 标签，`PromptDriftJob` 写出 `config.change` 审计；挪 test 标签不告警
- [ ] 审计 payload 里没有提示词原文
- [ ] 控制台切到 prod 时页面没有编辑和生效按钮

## 明确不做

- 不做 Langfuse Playground 那样的在线调试（平台管理员直接用 Langfuse 界面）
- 不做按提示词版本的指标对比页（深链到 Langfuse 提示词的 Metrics 页）
- 不做提示词审批流；staging 生效靠回归门禁兜底，prod 靠 `keel release`
- 不管 Dify 智能体的提示词
- 不把代码里的提示词文件当版本库：Langfuse 是版本的唯一来源，`prompts/` 只是兜底副本和新版本的来源之一
