# P0-2 契约代码生成

## 目标

改一次 `contracts/` 下的 schema，重新生成后 Java 模型和 Python pydantic 模型同步变化，两边字段、枚举、可选性完全一致。

## 依赖

- 前置任务：P0-1、P0-1a（契约冻结）、P0-4（Maven 骨架）
- 依赖的契约文件：`manifest.schema.json`、`sse-events.schema.json`、`audit-event.schema.json`、`invoke.openapi.yaml`、`error-codes.yaml`
- 依赖的外部组件及其真实行为：
  - **待确认**：生成器选型。Java 侧候选 `jsonschema2pojo` 与 `openapi-generator`，Python 侧候选 `datamodel-code-generator`。三个都要先拿 `manifest.schema.json` 实际跑一遍再定，重点看下面「实现要点」里列的三件事能不能满足。**不要凭文档选型**，选完把结论写回本 spec

## 改哪些文件

```
pom.xml                                   # 只加 pluginManagement
keel-common/pom.xml
keel-common/src/main/java/com/keel/common/**   # 手写部分：错误码常量、工具类
keel-server/src/main/java/com/keel/server/common/**   # 仅当 ErrorCode 的包路径变化时改 import
sdk-python/pyproject.toml
sdk-python/keel/_generated/**
scripts/codegen.sh
```

生成产物的落盘位置见实现要点第一条。

## 接口契约

要生成的模型，两边一一对应：

| 契约文件 | Java（`com.keel.common.model`） | Python（`keel._generated`） |
|---|---|---|
| `manifest.schema.json` | `AgentManifest` | `manifest.py` |
| `sse-events.schema.json` | `StepEvent` `ToolEvent` `TokenEvent` `FinalEvent` `ErrorEvent` `SuspendEvent` | `events.py` |
| `audit-event.schema.json` | `AuditEvent` | `audit.py` |
| `error-codes.yaml` | `ErrorCode` 枚举 + `retryable` | `errors.py` |

## 实现要点

### 生成器选型记录（2026-10-03）

按本节要求先试跑三个候选。`jsonschema2pojo` 和 `openapi-generator` Maven 插件均未在本机缓存，离线调用失败；`datamodel-code-generator` 未安装，pip 包索引在当前环境无法解析，试跑失败。因此没有把未经实际验证的外部工具写进构建。采用仓库内 `scripts/codegen.py`（由 `codegen.sh` 调用）直接读取冻结 JSON Schema 和错误码 YAML 的简单映射，确定性产出两种语言模型。生成器对未知 schema 类型直接报错；加入字段后重跑即可同时更新两边。将来能联网时可重新试跑三个候选，再以本节验收项比较替换。

- **生成产物进不进 git，两边要一致，并且现在就定死**。建议都进 git（Java 放 `keel-common/src/main/java/com/keel/common/model/`，Python 放 `keel/_generated/`），理由是 Python SDK 要发 pip 包，用户 `pip install` 时不会执行代码生成；Java 侧如果放 `target/generated-sources` 而 Python 放源码目录，两边就会不同步。进 git 的代价是必须有 CI 检查：**跑一遍生成，`git diff --exit-code` 必须干净**，否则有人手改了生成代码也发现不了。这条检查是本任务的核心验收项。

- **枚举值带点号，Java 枚举装不下**。契约里有 `tool.call`、`agent.config`、`run.suspend`、`data.export` 这类值，Java 标识符不允许点号。生成器通常会转成 `TOOL_CALL`，序列化回去就变成了 `TOOL_CALL` 而不是 `tool.call`，**跨语言就对不上了**。必须确认生成器能产出 `@JsonValue` 注解保留原始字面量；做不到就在 `keel-common` 里手写这几个枚举，不要让生成器糊弄过去。这是最容易悄悄出错的地方。

- **可选字段不能生成成基本类型**。契约规则是「新增字段必须可选」，所以绝大多数字段都是 optional。Java 侧如果生成 `int`、`boolean`、`long`，缺失的字段会变成 `0` / `false` 而不是 `null`——调用方分不清「没传」和「传了 0」。必须生成包装类型（`Integer`、`Boolean`）。Python 侧对应 `Optional[int] = None`。

- **`additionalProperties` 的处理两边要一致**。契约是 `keel/v1`，平台要同时兼容两个小版本，意味着新版本智能体发来的未知字段，老版本平台**必须能解析而不是报错**。Java 侧 Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true`，要关掉；Python 侧 pydantic v2 默认 `extra='ignore'`，确认没被改成 `forbid`。两边都要有用例验证「多一个未知字段仍能解析」。

- **时间字段统一 RFC 3339 带时区**（`2026-09-29T12:49:30Z`）。Java 用 `Instant` 不要用 `LocalDateTime`（丢时区），Python 用 `datetime` 并确认序列化带 `Z` 而不是 `+00:00`——审计哈希链算的是规范化 JSON 的 sha256，这两种写法哈希不同，会直接导致 Java 写入的链在 Python 侧验不过。

- `error-codes.yaml` 的 `retryable` 要生成成代码里可直接读的属性，不要让 SDK 和网关各自判断（`AGENTS.md` 的要求）。

- **已有一份手写的 `com.keel.common.error.ErrorCode`**（P1-0 留下，只含 `SERVER_*` 三个，带 `TODO(P0-2)`），keel-server 的 `common/` 在用它的 `name()`、`retryable()`、`http()`、`message()`。生成的枚举**沿用这个全限定名和这四个访问方法**，直接覆盖手写版；做不到就同步改 keel-server 的 import 和调用，并保证 `mvn -o -pl :keel-server test` 仍通过。不要两份 `ErrorCode` 并存。

- Python 侧 `keel/protocol/{events.py,errors.py}` 和 `keel/manifest.py` 现在是只有 docstring 的占位，生成产物放 `keel/_generated/`，这几个文件只做 re-export，不复制字段。

## 验收标准

```bash
bash scripts/codegen.sh && git diff --exit-code    # 生成结果与仓库一致
mvn -o -pl :keel-common test
cd sdk-python && pytest tests/test_generated.py
```

- [ ] 跑一遍生成后 `git diff --exit-code` 干净，CI 里也有这一步
- [ ] 改 `manifest.schema.json` 加一个可选字段，重新生成，Java 和 Python 两边都出现该字段且为可选
- [ ] 枚举 `tool.call` 在 Java 侧序列化回 JSON 仍是 `tool.call`，不是 `TOOL_CALL`
- [ ] 带一个未知字段的 JSON，Java 和 Python 都能解析成功
- [ ] 同一个 `AuditEvent` 对象，Java 和 Python 序列化出的规范化 JSON 字节级相同（为 P0-3 的哈希链用例打底）

## 明确不做

- 不做 `console/` 的 TypeScript 类型生成（控制台走 `contracts/console-api.openapi.yaml`，在 P1-15）
- 不写任何业务逻辑，只生成模型
- 不做契约版本兼容层（同时支持 v1/v2 的转换）——v2 还不存在
