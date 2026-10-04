# P1-18 rag-forge 接入

## 目标

一次经 rag-forge 的检索，作为调用方 trace 里的 retriever 节点出现，下面能看到改写、向量、关键词、重排四段耗时。向量化模型不会降级到另一个模型。rag-forge 以共享服务登记。

## 依赖

- 前置任务：P1-1、P0-1
- 依赖的契约文件：`contracts/trace-attributes.md`、`contracts/manifest.schema.json`（`kind: Service` 的登记文件叫 `service.yaml`）
- 依赖的外部组件：rag-forge 仓库（不在本仓库）。薄网关使用别名 `rag-forge-{env}` 的虚拟 Key，向量化 Key 的 `allowFallback` 为 false。自部署 reranker 不经过薄网关。

## 改哪些文件

都在 rag-forge 仓库，外加本仓库一份登记用的说明，不改 rag-forge 的业务检索公式：

```
rag-forge: service.yaml
rag-forge: 检索入口与 RetrievalService 的 span
rag-forge: Micrometer 标签 caller_agent、kb
rag-forge: modelcenter 的模型地址
rag-forge: GET /api/v1/eval/summary
docs/specs/p1/P1-18-rag-forge.md
```

Deployment 增加标签 `keel.io/service: rag-forge`。就绪探针继续用已有的 `/actuator/health`。

## 接口契约

追踪：OTLP/HTTP 到 `{LANGFUSE_HOST}/api/public/otel/v1/traces`，请求头 `x-langfuse-ingestion-version: 4`。不使用 gRPC，不使用 `OtlpGrpcSpanExporter`。

检索入口继续读取 W3C `traceparent`，span 挂在调用方的 trace 上。

```
GET /api/v1/eval/summary?kb=
{ recallAt5, zeroResultRate, evaluatedAt }
```

这是给控制台共享服务页的只读摘要。rag-forge 自己的检索评测台保留。

## 实现要点

- **向量化模型 pin 死，禁止 fallback。** 降级到另一个 embedding 模型后，新向量和库里的存量向量不在同一空间，检索不报错，只是召回变差。改写和 judge 可以降级。
- **reranker 仍走自部署服务。** 薄网关没有 `/rerank`。若以后改云厂商 rerank，先单独核实那家的接口，未核实前不要写进网关。
- **分段耗时写成 retriever 的子 span：** rewrite、vector、keyword、rerank。属性带 `kb`、`top_k`、分数。不把文档原文放进 span 属性。
- **指标标签只加 `caller_agent` 和 `kb`。** `caller_agent` 来自 JWT 的 `azp`，没有则来自约定请求头。不要用用户显示名当标签。
- **审计走 RocketMQ 的 `config.change`：** 知识库增删、文档导入、破玻璃提权、API Key 变更。本地审计表先留着。不要做成同步阻断检索。
- 模型地址改为薄网关后，`model_usage_daily` 仍按组织记账，不要删。

## 验收标准

```bash
# 在 rag-forge 仓库
mvn -o test
```

再发一次带 `traceparent` 的检索：

- [ ] Langfuse 里该 trace 有 retriever 节点，四个子 span 都在
- [ ] embedding 的配置里没有 fallback 模型
- [ ] Micrometer 导出的检索指标带 `caller_agent` 和 `kb`
- [ ] `GET /api/v1/eval/summary?kb=` 返回三个字段，缺数据时为 null 而不是 500
- [ ] 仓库和配置样例里没有厂商 API Key

## 明确不做

- 不让 rag-forge 走评测门禁（它是 Service）
- 不把检索评测台迁到 Keel 控制台
- 不改向量维度和索引结构
- 不在本任务改 askdb（P1-17）
