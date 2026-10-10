# P2-18 智能体调试与删除

## 目标

控制台可以直接调试一个已注册的智能体，按它的交互形态（对话、任务、后台、定时）给出不同的调试页。已下线的智能体可以从注册中心删除。

## 依赖

- 前置任务：P1-4 注册中心、DF-9b 智能体详情抽屉。后端已有 `POST /agents/{name}/chat`、`POST /agents/{name}/retire`、`GET /insight/traces`。
- 依赖的契约文件：`contracts/manifest.schema.json`、`contracts/console-api.openapi.yaml`、`contracts/error-codes.yaml`。
- 外部组件：无。调试只调 keel-server，不直连智能体、Langfuse、薄网关。

## 改哪些文件

```
docs/specs/p2/P2-18-agent-debug-delete.md
contracts/manifest.schema.json
contracts/console-api.openapi.yaml
contracts/error-codes.yaml
contracts/tests/cases.yaml
contracts/tests/fixtures/manifest/valid/schedule.yaml
contracts/tests/fixtures/manifest/invalid/schedule-without-cron.yaml
keel-common/src/main/java/com/keel/common/**          # codegen 产物
sdk-python/keel/_generated/**                         # codegen 产物
console/src/api/http.ts
console/src/api/agents.ts
console/src/api/schema.d.ts                           # npm run gen:api 产物
console/src/router/index.ts
console/src/views/agents/AgentDetail.vue
console/src/views/agents/AgentDebug.vue
console/src/views/agents/debug.ts
console/src/views/agents/debug.test.ts
docs/console/Keel-控制台前端.html
docs/architecture/Keel-技术文档.html
```

## 设计结论

### 调试放在哪

- 抽屉里**不加**调试页签。抽屉是「查资料」的地方，最宽 620px，放不下对话和执行过程；页签切换还会丢掉正在进行的会话。
- 抽屉底部加「调试」按钮，进入控制台内的独立页面 `/agents/:name/debug`。同一个浏览器标签页内跳转，保留左侧导航、环境切换和登录态；它是普通链接，需要并排对照时可以 ⌘/Ctrl 点击在新标签页打开。
- 调试页左上角「← 返回详情」回到 `/agents?drawer={name}`。

### 交互形态从哪来

manifest 新增可选字段 `spec.interaction`（只对 `kind: Agent`）：

| `mode` | 含义 | 例子 | 调试页主区 |
|---|---|---|---|
| `chat`（缺省） | 用户多轮问答 | careermate、cs-bot、askdb | 对话窗口 |
| `task` | 提交一件事，看执行步骤和产出 | offshore-wind 检修建议、prd-agent | 任务输入 + 执行步骤 + 结果 |
| `service` | 没有用户入口，由事件、其他系统或上级智能体调用 | code-review（PR webhook）、被编排的子智能体 | 触发方式 + 最近运行 + 发送测试输入 |
| `schedule` | 按 cron 定时运行 | night-patrol | 运行计划 + 最近运行 + 立即运行一次 |

- `schedule`：5 段 cron，`mode: schedule` 时必填。`timezone` 缺省 `Asia/Shanghai`。
- `trigger`：给人看的一句话，说明谁触发它，`service` 用。
- 缺省 `chat`，与现在唯一的调用入口 `/chat` 一致。新增字段可选，不破坏 keel/v1。
- 所有形态底下都是同一个 `/v1/invoke` 协议，差别只在控制台怎么呈现。

### 调试页公共部分

- 顶部：头像、名称、状态、形态、环境 · 版本、「全部链路」。
- 提示条：调试是真实调用，消耗该智能体预算，写链路和审计；高风险工具照常走审批。
- 右侧「本页调用」：本页发出的每次调用，带耗时和链路链接 `/traces/:id`。
- 不能调试：`DRAFT`（还没注册）、`RETIRED`（Key 已吊销）只显示说明。`OFFLINE` 显示警告但允许尝试。
- 发送类按钮都加 `v-write`，预览身份（只读登录）不能调用。

### 各形态

- 对话：Enter 发送，Shift+Enter 换行；每条回复带链路链接；「清空」只清前端。
- 任务：输入任务说明后运行；结束后按 traceId 读 `/insight/traces/{id}` 展示执行步骤（节点名、类型、耗时、状态）和最终输出。Langfuse 还没写入时提示稍后刷新。
- 后台：展示 `trigger`；最近运行读 `GET /insight/traces?agent={name}`；「发送测试输入」复用任务的运行区。
- 定时：展示 cron、时区和常见写法的中文说明；最近运行同上；「立即运行一次」复用任务的运行区，不改变定时计划。

### 删除

- 只有 `status = RETIRED` 才显示「删除」，在抽屉底部「已下线」说明旁边。没下线时不显示；下线弹窗里说明下线后可以删除。
- 弹窗要求输入智能体 ID 确认，说明删掉什么、保留什么。
- `DELETE /agents/{name}`：未下线返回 409 `AGENT_NOT_RETIRED`。成功后关闭抽屉、刷新列表。
- 删除含义：从注册中心列表、搜索、谱系中移除；审计（`keel_audit` 只增不删）和 Langfuse 里的链路、评测保留到各自保留期。**名称不释放**，再注册同名返回 `AGENT_NAME_TAKEN`，避免新智能体和旧审计、旧链路混在一起。

## 验收标准

```bash
python3 scripts/codegen.py && git diff --exit-code keel-common sdk-python/keel/_generated   # 产物已提交
cd sdk-python && pytest tests/contract
cd console && npm run gen:api && npx vitest run src/views/agents/debug.test.ts && npm run typecheck
```

- [ ] 抽屉「调试」进入 `/agents/:name/debug`，返回能回到原抽屉
- [ ] 四种形态各自的主区能渲染；缺 `interaction` 时按对话
- [ ] DRAFT、RETIRED 不能调试；只读身份看得到页面但发不出调用
- [ ] 已下线才出现「删除」；输入 ID 后才能确认；成功后列表里没有它

## 明确不做

- 后端 `DELETE /agents/{name}` 的实现：软删除列、`agent.delete` 高风险同步审计（需在 `audit-event.schema.json` 的 action 枚举登记）、名称占用检查。另开 keel-server 任务。
- TODO：`/chat` 每轮独立、不带上文，且服务端写死 env=dev。多轮会话和按智能体环境调用要等 chat 接口带 `sessionId`、`env`。
- TODO：任务式没有流式步骤，结束后才从链路读回。要实时步骤需要控制台侧的 SSE 转发接口。
- TODO：定时任务的下次运行时间、暂停 / 恢复计划。需要后端提供 `nextRunAt` 和调度接口。
- 任务式按 manifest 声明的输入结构生成表单（现在只有一个文本框）。
