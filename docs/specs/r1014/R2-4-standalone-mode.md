# R2-4 独立运行模式

## 目标

`KEEL_MODE=standalone` 时，loop-agent 不注册、不连薄网关，也能在本地跑完一次工具调用。模型打到调用方给出的 OpenAI 兼容地址，提示词只读本地 `prompts/`，审计写入 keel-lite，追踪在未配置 Langfuse 时关闭。`KEEL_ENV=prod` 或 Spring `prod` profile 下拒绝启动。

## 依赖

- 前置任务：R1-3。
- 依赖的契约文件：无新字段。审计事件仍用现有 `audit-event` 枚举，环境只能是 `dev`、`test`、`staging`。
- 依赖的外部组件及其真实行为：独立模式不调用 Langfuse、薄网关或 auth-gateway。模型地址只来自 `KEEL_LLM_BASE_URL`。

## 改哪些文件

```
docs/specs/r1014/R2-4-standalone-mode.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
sdk-python/keel/runtime.py
sdk-python/keel/agent.py
sdk-python/keel/llm/client.py
sdk-python/keel/prompts.py
sdk-python/keel/context.py
sdk-python/keel/audit/lite_reporter.py
sdk-python/tests/runtime/test_standalone.py
keel-spring-boot-starter/src/main/java/com/keel/starter/manifest/ManifestLoader.java
keel-spring-boot-starter/src/test/java/com/keel/starter/manifest/StandaloneModeTest.java
```

## 接口契约

- `KEEL_MODE` 缺省或空字符串是 `governed`。只接受 `governed` 和 `standalone`。
- `standalone` 要求 `KEEL_LLM_BASE_URL`（manifest 声明了模型时）和 `KEEL_ENV=dev|test|staging`。不要求 `KEEL_LLM_KEY`、`KEEL_AUDIT_URL`、`KEEL_SERVER_URL`、Langfuse。
- `KEEL_ENV=prod`，或 `SPRING_PROFILES_ACTIVE` 含 `prod` 时，`KEEL_MODE=standalone` 启动失败。
- 审计写入项目目录 `.keel/dev.db`。未设置 Langfuse 时不创建导出器。

## 界面对齐

无。`ui-checklist.md` 没有 R2-4 行；本任务不改控制台。

## 实现要点

- 独立模式的聊天请求不附带薄网关的 `caller_agent` 元数据。上游不返回费用时不按「成本被记成 0」告警。
- 未提供 `KEEL_LLM_KEY` 时，客户端使用字面量 `standalone` 作为 Bearer，便于不鉴权的本地兼容服务。这不是虚拟 Key，也不写入仓库。
- manifest 即使把提示词来源写成 `langfuse`，独立模式也只读 `prompts/`，不访问 Langfuse。
- 受治理模式的必填变量保持不变。

## 验收标准

```bash
cd sdk-python && ../.venv/bin/python -m pytest tests/runtime/test_standalone.py tests/test_runtime.py tests/loop/test_loop.py -q
mvn -o -pl :keel-spring-boot-starter test
```

- [x] 假的 OpenAI 兼容服务上，loop-agent 完成一次工具调用并把审计写入 keel-lite；请求不发往薄网关，也不要求 Langfuse。
- [x] `KEEL_ENV=prod` 与 Spring `prod` profile 都不能开启 standalone。
- [x] 未设置 `KEEL_MODE` 时，原有受治理启动检查仍然要求模型和审计变量。

## 明确不做

- 不在独立模式里注册智能体、换票或调用薄网关管理接口。
- 不把独立模式做成默认值。
- 不改 loop-agent 模板的业务代码。
