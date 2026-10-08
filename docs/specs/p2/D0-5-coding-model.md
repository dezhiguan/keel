# D0-5 薄网关编码模型

## 目标

`keel-llm` 能按人民币单价调用 `qwen3.8-flash`。缺单价时仍拒绝启动。

## 依赖

- 前置任务：P1-2。
- 依赖的契约文件：无。模型名不进 `keel/v1` 契约。
- 依赖的外部组件及其真实行为：2026-10-08 阿里云百炼中国站模型价格页，华北 2（北京）与全球部署的标准价相同。`qwen3.8-flash`，非思考与思考模式，0 < Token ≤ 1M：输入 0.8 元/百万 tokens，输出 2.7 元/百万 tokens。缓存命中价不采用，网关按标准价记成本。上游沿用 DashScope 的 OpenAI 兼容地址。

## 改哪些文件

```
docs/specs/p2/D0-5-coding-model.md
keel-llm/src/main/resources/application.yaml
keel-llm/src/test/java/com/keel/llm/GatewayTest.java
```

## 接口契约

不新增接口。`GET /admin/v1/models` 会多返回这一个已定价的模型。调用仍是 `POST /v1/chat/completions`，请求里的 `model` 为 `qwen3.8-flash`。

单价写成每 token 人民币：输入 `0.0000008`，输出 `0.0000027`。

## 实现要点

- 单价写在配置里，不写进代码默认值。
- 厂商密钥继续用 `QWEN_API_KEY`，不进 git。
- 不改 `qwen-plus` 与 `deepseek-v3` 的占位单价。

## 验收标准

```bash
mvn -o -pl :keel-llm -Dtest=GatewayTest test
```

- [ ] 配置里的 `qwen3.8-flash` 输入、输出单价等于上面的标准价
- [ ] 单价大于 0，目录可以装入该模型

## 明确不做

- 不改 Langfuse Model Definitions。
- 不把缓存折扣写进网关。
