# P2-17 控制台读取性能

## 目标

减少总览、共享服务、链路追踪、评测和提示词页面的等待时间，不改变现有响应结构、权限和数据缺失语义。

## 依据与范围

- 2026-10-08 线上低请求量测量：共享服务接口 1.05–2.91 秒；链路接口 0.95–1.39 秒且偶发 500；提示词接口一次 10.48 秒后 500。服务端异常需用 traceId 查日志，不能由本次改动猜测修复。
- P2-12、P2-16、P1-13、P1-14、P1-15 的接口与行为继续有效。
- 不改跨语言契约，不改外部组件 API，不对线上执行压测。

## 改动文件

```
docs/specs/p2/P2-17-console-performance.md
keel-server/src/main/java/com/keel/server/insight/SharedServiceMonitor.java
keel-server/src/main/java/com/keel/server/insight/TraceQueryService.java
keel-server/src/main/java/com/keel/server/prompt/PromptService.java
keel-server/src/main/java/com/keel/server/release/ReleaseService.java
keel-server/src/test/java/com/keel/server/insight/SharedServiceMonitorTest.java
keel-server/src/test/java/com/keel/server/insight/TraceQueryServiceTest.java
keel-server/src/test/java/com/keel/server/prompt/PromptFlowTest.java
keel-server/src/test/java/com/keel/server/prompt/PromptServiceCacheTest.java
console/src/main.ts
deploy/docker/console-nginx.conf
```

## 实现

- 共享服务独立外部读取并行执行，知识库评测摘要每批最多并行四个；结果短暂缓存，缓存过期时返回上一份并后台刷新。空值保持空值，不把故障当成健康。
- 提示词列表避免串行按版本读取；对上游并发设限，写操作后不能展示旧数据。
- 链路列表用请求时间范围限制 Langfuse 读取，并按范围复用短期缓存；不改变服务端筛选和分页结果。
- 静态 JS/CSS 启用 gzip；Element Plus 只注册页面实际使用的组件和指令。

## 验收

- 对应 Java 测试通过，控制台类型检查、测试和构建通过。
- 构建后检查主 JS/CSS 大小和 nginx 配置。
- 数据无变化时重复读共享服务和链路列表不会重复打慢上游；写提示词后列表立即更新。
