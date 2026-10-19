# R1019 超时用例从门禁分母丢失

实际 qa-tools 数据集52条，结果47条且PASSED；日志有5条失败（超时/断流）。qa-guard数据集51条，结果50条、1.00且PASSED，日志Item45超时。SDK experiment.py 文档及 _client/client.py asyncio.gather 后明确只保留成功 ExperimentItemResult，不能直接用其列表当整个数据集。

运行前固定所有 ACTIVE 用例 ID 集；汇总前要求返回 ID 无缺失、无重复、无多余。任何不完整的实验作为执行失败，不计算部分高分，不写通过结论。已完成的旧结果不伪造改分，验收中标为无效证据，以新版本完整重跑为准。每条仍按实际输出评分，保留0.95门槛和回归条件。

测试覆盖全部成功、缺失一条、重复ID、额外ID、非ACTIVE项。线上逐个核对4个数据集（tools52，其余51）的完整条数。

## QA运行配置

真实日志确认若干请求在60秒读超时前未收到有效输出。Qwen3.8 默认思考预算较大；这四个值班检索验收任务使用非思考模式，独立配置仅作用于4把QA dev Key的qwen3.8-flash，保留其他模型及现有元智能体参数，不增加超时/预算、不降低评分。

参数依据[百炼官方 Chat API](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions/)在2026-10-19核对：enable_thinking=false适用Qwen3.8，HTTP顶层传参。配置由现有网关RequestDefaults应用；configure_request_defaults.py只合并这4个scope，Secret其他内容不变且不输出。手工运维部署阶段执行后滚动网关，然后完整重跑。
