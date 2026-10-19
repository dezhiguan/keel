# R1019 评测并发实际串行

运行 ev_402403f519c94fd88cbff799b3dfe09f 超过十分钟仍未结束。核对当前安装 Langfuse experiment._run_task：同步 task 在事件循环直接执行，不提交线程池。worker 的同步 httpx 调用阻塞循环，max_concurrency=4 实际无法并发。

将远程调用包装为异步 task，经 asyncio.to_thread 执行阻塞 HTTP，继承 contextvars 中当前评测 span。保留 SDK 最大并发四条、每请求超时和 Job 总时限；不自动重跑或重复计费。测试证明两个调用能同时进入，并保留 trace context。评测结果和门禁算法保持原样。
