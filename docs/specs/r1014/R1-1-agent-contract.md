# R1-1 真智能体基础契约

## 目标

`keel/v1` manifest 可声明 `spec.kind: agent|workflow`，Python SDK 与 Java starter 共用循环步数追踪属性和错误码。

## 依赖

- 前置任务：阶段 0 完成。
- 依赖的契约文件：`contracts/manifest.schema.json`、`contracts/trace-attributes.md`、`contracts/error-codes.yaml`。
- 依赖的外部组件及其真实行为：无新增外部接口。

## 改哪些文件

```
docs/specs/r1014/R1-1-agent-contract.md
docs/specs/r1014/README.md
contracts/manifest.schema.json
contracts/trace-attributes.md
contracts/error-codes.yaml
contracts/tests/cases.yaml
contracts/tests/fixtures/manifest/valid/agent-kind.yaml
contracts/tests/fixtures/manifest/invalid/unknown-spec-kind.yaml
contracts/examples/agent-kind.yaml
contracts/examples/invalid/07-unknown-spec-kind.yaml
keel-common/src/main/java/com/keel/common/model/AgentManifest.java
keel-common/src/main/java/com/keel/common/error/ErrorCode.java
sdk-python/keel/_generated/manifest.py
sdk-python/keel/_generated/errors.py
sdk-python/keel/tracing/attrs.py
keel-spring-boot-starter/src/main/java/com/keel/starter/tracing/SpanAttributes.java
sdk-python/tests/test_generated.py
sdk-python/tests/tracing/test_tracing.py
keel-spring-boot-starter/src/test/java/com/keel/starter/tracing/TracingTest.java
```

## 接口契约

- `spec.kind`：可选枚举 `agent | workflow`。顶层 `kind: Agent | Service` 仍表示资源类型，两者互不替代。旧 manifest 缺省保持兼容，注册时只对显式 `agent` 执行后续 R1-8 的要素检查。
- `keel.loop.step`：非负整数，从 1 开始；每个 loop 步骤的 span 设置该属性。
- `AGENT_MAX_STEPS_EXCEEDED`：非重试错误，HTTP 409。`PROMPT_VARIABLE_MISSING` 已存在，保持其现有消息、重试性和 HTTP 400 不变。

## 界面对齐

无。`ui-checklist.md` 无 R1-1 行；智能体详情的运行方式和要素检查分别由 R1-3、R1-8 实现。

## 实现要点

- 先改 `contracts/`，再运行 `scripts/codegen.py` 同步 Java / Python 生成模型。
- 新字段可选，不改 `keel/v1` 版本，也不改变旧 manifest 的解析。
- Python 与 Java 追踪常量集合继续与 `trace-attributes.md` 完全一致。

## 验收标准

```bash
.venv/bin/python scripts/check_contracts.py
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/contract tests/test_generated.py tests/tracing/test_tracing.py -q
mvn -o -pl :keel-spring-boot-starter test
```

- [x] 新字段的正例通过、错误枚举值被拒，旧 manifest 仍通过。
- [x] Java / Python 生成模型均含 `spec.kind`；错误码元数据一致。
- [x] 两种语言的追踪常量与契约一致。

## 明确不做

- 不实现 `ctx.loop`、注册自检或控制台要素检查，分别归 R1-3、R1-8。
