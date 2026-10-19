# R1019 取消后的模型节点状态

页面停止真实SSE调用后，链路列表root为失败，取消对话未写PG；实际SDK链路是6bfc00ef73705095600fb76e5725b11e（浏览器HTTP traceparent不是此root，需从列表问题定位）。详情generation节点llm.chat仍显示ok，而运行被取消。证据cancel-trace.json、cancel-trace-detail.txt/png。

LlmClient显式处理asyncio.CancelledError：generation标failed，并记录常量「调用已取消」，继续抛出取消，不伪造最终回答或usage。用流式MockTransport真实挂起并取消任务，验证取消传播、span状态和requestId。

该调用无最终usage所以网关PENDING、费用null、保留预留，符合既有R4-1/R0-3要求，不应改成免费或释放可能已经发生的厂商费用；不更改账单政策。
