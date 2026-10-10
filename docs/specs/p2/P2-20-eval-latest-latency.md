# P2-20 评测中心 latest 不再翻历史

## 目标

`GET /eval/{agent}/latest` 在 2 秒内返回。2026-10-11 线上打开评测中心，这条接口 11.1 秒，响应体 782 字节。时间在服务端等 Langfuse。

## 原因

Langfuse `GET /api/public/experiments` 和 `GET /api/public/experiment-items` 的 `fromStartTime` 必填（2026-10-11 读 Cloud OpenAPI）。窗口越大查询越慢。结果按最新活动时间倒序。现有实现从 `2020-01-01` 起每页 100 条、最多翻 10 页，找不到再全量翻一遍。

## 做法

- 评测页只取一页，`limit=20`，不跟 cursor。先查近 90 天；这一页为空再查 `2020-01-01` 起的一页。
- experiment-items 用实验时间前 1 天到后 14 天。这个窗口没有条目时，再退回 2020 起的一页查询。
- 数据集索引缓存 60 秒，单智能体结果缓存 45 秒。`POST /eval/{agent}/runs` 丢掉该智能体的缓存再读。
- 总览用的全量 experiments 列表不动。

## 改哪些文件

```
docs/specs/p2/P2-20-eval-latest-latency.md
.cursor/rules/external-apis.mdc
docs/architecture/Keel-技术文档.html
keel-server/src/main/java/com/keel/server/integration/langfuse/LangfuseClient.java
keel-server/src/main/java/com/keel/server/insight/EvalQueryService.java
keel-server/src/test/java/com/keel/server/insight/EvalQueryServiceTest.java
```

## 验收

```bash
mvn -o -pl :keel-server test -Dtest=EvalQueryServiceTest
```

- [ ] 近 90 天有记录时，experiments 只请求一次，查询里没有 `2020-01-01`，也不带 `cursor`
- [ ] 近 90 天为空时，只再请求一页 2020 起的记录，仍不跟 cursor
- [ ] 45 秒内再次打开同一智能体不再打 Langfuse；点运行回归会再读一次
