# D0-6 薄网关目录换成现行模型并对齐官方单价

## 目标

薄网关目录里的两个对话模型换成 `qwen3.8-flash` 和 `deepseek-flash`。控制台「输入 / 输出 / 缓存」按官方价展示，单位仍是人民币 / token。

## 依赖

- 前置任务：P1-2、D0-5。
- 依赖的契约文件：无。模型名不进 `keel/v1`。
- 依赖的外部组件及其真实行为：
  - 2026-10-18 阿里云百炼模型页，华北 2（北京）。`qwen3.8-flash` 在 1M 以内、思考与非思考同价：输入 0.8 元/百万 tokens，输出 2.7 元/百万 tokens，缓存命中输入 0.1 元/百万 tokens。
  - 2026-10-18 DeepSeek Models & Pricing。`deepseek-flash` 由 DeepSeek-V4.1-Flash-1017 提供。闲时：缓存未命中输入 $0.15、输出 $0.60、缓存命中 $0.003 / 百万 tokens。忙时为两倍。网关只有一档单价，记闲时价。
  - 人民币按 2026-10-16 中国人民银行美元中间价 6.7220 折算（10 月 17、18 日为周末，无新中间价）。折算后输入 1.0083 元、输出 4.0332 元、缓存命中 0.020166 元 / 百万 tokens。

## 改哪些文件

```
docs/specs/p2/D0-6-catalog-prices.md
keel-llm/src/main/resources/application.yaml
keel-llm/src/test/java/com/keel/llm/GatewayTest.java
keel-server/src/main/java/com/keel/server/insight/CostService.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
keel-server/src/main/java/com/keel/server/registry/controller/CatalogController.java
keel-server/src/main/java/com/keel/server/registry/service/RegisterManifest.java
keel-server/src/main/java/com/keel/server/registry/service/CareerMateRegistrar.java
keel-server/src/main/java/com/keel/server/provisioning/EchoCredentialWriter.java
deploy/ci/provision-meta-agent.py
ci/backend-gate.sh
```

## 接口契约

不新增接口。`GET /admin/v1/models` 返回这两个模型，以及 `inputCnyPerToken`、`outputCnyPerToken`、`cacheCnyPerToken`。

## 实现要点

- 目录不再保留 `qwen-plus`、`deepseek-v3`。
- `qwen3.8-flash` 这个名字里有点号。Spring 绑 Map 时会把点号当成下一层，键要写成 `"[qwen3.8-flash]"`，绑出来的名字仍是 `qwen3.8-flash`。
- 缓存单价写入 `cache-cny-per-token`。上游 usage 带 `cached_tokens` 时按这个单价计，不再按输入价计。
- `deepseek-flash` 上游仍是 `https://api.deepseek.com`。上下文 1M，最大输出 384K。
- 控制台可选模型和新建 Key 的默认模型改成这两个名字。已发出的虚拟 Key 仍写着旧模型名，要重新开通才会改。

## 验收标准

```bash
mvn -o -pl :keel-llm -Dtest=GatewayTest#codingModelUsesTheSameDayListPrice test
mvn -o -pl :keel-server -Dtest=CostServiceTest test
```

- [ ] 配置里只有 `qwen3.8-flash` 与 `deepseek-flash`，单价等于上面的折算结果
- [ ] 两个单价都大于 0，目录可以装入

## 明确不做

- 不按忙闲自动切换 DeepSeek 单价。
- 不改已经写在各智能体 manifest 里的模型名。
- 不改 Langfuse Model Definitions。
