# P2-15 提示词：契约与 SDK / starter 读取

## 目标

智能体代码用 `ctx.prompt("answer")`（Python）/ `ctx.prompt("answer")`（Java）拿到当前环境该用的提示词版本，编译后交给 `ctx.llm`，generation 上记录提示词名和版本号。Langfuse 不可用时用 `prompts/` 本地副本兜底。

## 依赖

- 前置任务：P0-1（manifest 契约）、P0-7 / P0-12（generation span 上报）、P1-1（Langfuse 项目）
- 依赖的契约文件：`contracts/manifest.schema.json` 的 `spec.prompts`；`contracts/trace-attributes.md`；`contracts/error-codes.yaml`
- 依赖的外部组件及其真实行为（2026-10-07 按官方文档核实，未实测）：
  - 四个环境共用一个 Langfuse 项目（2026-10-07 定），智能体用的就是 Secret 里现有的那对项目 Key。
  - `GET /api/public/v2/prompts/{name}?label={label}`，Basic Auth。名字里的 `/` 要 URL 编码。
  - 标签不存在返回 404，不会退回 production 或 latest。
  - generation 关联属性：`langfuse.observation.prompt.name`（字符串）、`langfuse.observation.prompt.version`（整数）。用兜底副本时 Langfuse 不建关联。
  - 提示词有 text 和 chat 两种，变量语法 `{{var}}`。
  - 待确认：提示词接口的限流。开工时用一个测试提示词实测一次读回带 prompt 属性的 generation。

## 改哪些文件

```
contracts/manifest.schema.json
contracts/trace-attributes.md
contracts/error-codes.yaml
contracts/tests/**
sdk-python/keel/prompts.py
sdk-python/keel/context.py
sdk-python/keel/llm/client.py
sdk-python/keel/manifest.py
sdk-python/keel/_generated/**
sdk-python/keel/cli/prompts.py
sdk-python/keel/cli/__main__.py
sdk-python/tests/prompts/**
keel-spring-boot-starter/src/main/java/com/keel/starter/context/PromptClient.java
keel-spring-boot-starter/src/main/java/com/keel/starter/context/KeelPrompt.java
keel-spring-boot-starter/src/main/java/com/keel/starter/context/KeelContext.java
keel-spring-boot-starter/src/main/java/com/keel/starter/context/LlmClient.java
keel-spring-boot-starter/src/test/java/com/keel/starter/prompt/**
templates/*/prompts/**
templates/*/agent.yaml
docs/specs/p2/P2-15-prompt-sdk.md
```

## 接口契约

先改 `contracts/`，再生成 Python / Java 模型。全部是新增可选字段，留在 `keel/v1`。

manifest：

```yaml
prompts:
  source: langfuse | local     # 已有
  label: production            # 已有：prod 读的标签。dev、test、staging 读同名标签
  items:                       # 新增，可选，默认 []
    - name: answer             # ^[a-z][a-z0-9-]{0,38}[a-z0-9]$；Langfuse 里的全名是 {metadata.name}/answer
      type: chat               # text | chat，与 Langfuse 里的类型一致
```

trace 属性（`contracts/trace-attributes.md` 新增三行，只出现在 generation 上）：

| 属性 | 值 | 何时写 |
|---|---|---|
| `langfuse.observation.prompt.name` | `offshore-wind/answer` | 用的是 Langfuse 版本 |
| `langfuse.observation.prompt.version` | 整数 | 同上 |
| `keel.prompt.fallback` | `true` | 用的是 `prompts/` 本地副本，此时不写上面两项 |

错误码（登记到 `contracts/error-codes.yaml`，新前缀 `PROMPT_`）：`PROMPT_NOT_DECLARED`、`PROMPT_UNAVAILABLE`、`PROMPT_VARIABLE_MISSING`、`PROMPT_TYPE_MISMATCH`。P2-16 再加 `PROMPT_NOT_GATED`。

SDK：

```python
p = await ctx.prompt("answer")          # -> Prompt(name, version | None, type, fallback: bool, config)
messages = p.compile(question=..., docs=...)
await ctx.llm.chat(messages=messages, prompt=p)
```

```java
KeelPrompt p = ctx.prompt("answer");
ctx.llm().chat(p.compile(Map.of("question", q)), p);
```

CLI：
- `keel prompts pull [--env staging]`：把该环境标签所在版本写到 `prompts/{name}.md`（text）或 `.json`（chat），用来刷新兜底副本。
- `keel prompts push --message "…"`：本地文件经 keel-server 建新版本，不挪标签（接口见 P2-16）。
- `keel register` 和 CI 模板自动带上 `prompts/` 各文件的内容和哈希，由 keel-server 判断要不要建新版本（P2-16）。
- `keel dev --local-prompts`：本机直接读 `prompts/` 文件，改完即生效，不请求 Langfuse。

## 实现要点

- **标签按环境选，不读 manifest 以外的配置。** `prod` 读 `prompts.label`（默认 `production`）；`dev`、`test`、`staging` 读同名标签。`source: local` 或 `keel dev --local-prompts` 只读 `prompts/` 本地文件，不请求 Langfuse。环境取 `KEEL_ENV`，没有就启动失败，不要默认成 prod。
- **dev、test 标签不存在时（返回 404）退到本地副本并标 fallback，staging、prod 不退。** staging、prod 的标签不存在说明发布流程出了问题，要报错暴露出来；dev、test 是新智能体刚注册、还没人挪过标签的常见情况。
- **只能取 manifest 里声明的提示词。** `ctx.prompt("x")` 里 x 不在 `items` 里，抛 `PROMPT_NOT_DECLARED`，不去 Langfuse 试。
- **缓存：进程内 60 秒，过期先返回旧值、后台刷新。** 启动时预取全部 `items`。prod 预取失败且本地没有副本，`/v1/health` 返回未就绪；staging 只告警。
- **兜底顺序：缓存 → Langfuse → 本地副本 → `PROMPT_UNAVAILABLE`。** 用本地副本时 `p.fallback = True`、`p.version = None`，日志带 `trace_id` 和 `agent` 打一条 WARN。
- **`compile` 缺变量直接抛 `PROMPT_VARIABLE_MISSING`。** 不能把 `{{x}}` 原样发给模型。多余变量忽略。
- **类型和 manifest 不一致时抛 `PROMPT_TYPE_MISMATCH`。** text 提示词编译成字符串，chat 编译成消息列表。
- **`config` 只透传 `temperature`、`max_tokens`、`top_p` 给 `ctx.llm`。** 写了 `model` 也忽略并打 WARN，模型只由 manifest 决定。
- **`ctx.llm` 不传 `prompt=` 时照常调用，不写提示词属性。** 但 `keel dev` 在 manifest 声明了 `items`、而一次运行里没有任何 generation 带提示词属性时，打印提醒。
- **提示词原文只进 generation 的 `langfuse.observation.input`**，和现在一样；不进审计、不进其他 span 属性。
- Python 和 Java 各自直接调这一个 GET 接口，不引 Langfuse SDK 的 `get_prompt`，保证两边缓存和兜底行为一致，用同一套 `contracts/tests` 用例验证。
- 测试用假的 Langfuse HTTP 服务（返回 200、404、超时三种），不要 mock 整个 client 类。

## 验收标准

```bash
cd sdk-python && pytest tests/prompts tests/contract
mvn -o -pl :keel-spring-boot-starter test
```

- [ ] dev、test、staging 分别请求 `label=dev`、`label=test`、`label=staging`，prod 是 `label=production`；`--local-prompts` 不发任何请求
- [ ] 名字 `offshore-wind/answer` 在请求路径里被编码成 `offshore-wind%2Fanswer`
- [ ] staging 或 prod 下假 Langfuse 返回 404 且没有本地副本时，抛 `PROMPT_UNAVAILABLE`，不退回其他标签；test 下 404 用本地副本并标 fallback
- [ ] 60 秒内第二次 `ctx.prompt` 不发请求；过期后先返回旧版本，下一次拿到新版本
- [ ] 带 `prompt=p` 的 generation 有 name 和 version 两个属性，version 是整数；用本地副本时只有 `keel.prompt.fallback=true`
- [ ] 缺变量抛 `PROMPT_VARIABLE_MISSING`；`config.model` 不影响实际调用的模型
- [ ] Python 和 Java 通过同一组 `contracts/tests` 用例

## 明确不做

- 不做控制台编辑、标签挪动、跨项目同步（P2-16）
- 不管 Dify 智能体的提示词
- 不支持跨智能体共用提示词，不支持 Langfuse 的提示词引用（prompt composition）
