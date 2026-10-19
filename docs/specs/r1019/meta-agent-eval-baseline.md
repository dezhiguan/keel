# R1019 元智能体质量基线

升级元智能体之前，先固定一套由契约反推的评测和门禁。用例覆盖拒绝谱系、名字与模板、工具登记、评测扩充、评审快照、交付编号、输入注入，以及草稿清单的结构、风险、委派和改稿。评分只看这些不变量，不把模型自由发挥的风险措辞算进分母。

数据集名保持已注册的 `meta-agent/dev-employee-specs`，避免重新开通。门槛 `minScore: 0.99`、`maxRegression: 0.02`。当前 16 个标签、每标签最多 4 条；任意一条变成 0 分，总分都会低于 0.99。相对已发布基线，单个标签下降超过 2 分也失败。

线上评测任务克隆元智能体容器，但调用 `/v1/invoke` 时必须带上该容器里的 `KEEL_META_AGENT_TOKEN`，请求头 `X-Keel-Bridge`。否则服务身份校验会把整次门禁打成 401。没配置该变量的其他智能体不发送这个头。

旧的两条占位例会和新基线一起留在数据集里，导入时按内容去重，不删除历史条目。平台评测只跑 Langfuse 中仍为 ACTIVE 的条目；部署后若发现占位例仍是 ACTIVE，要先停用再发起评测，避免挂起路径把整次实验判成不完整。

验收：

```bash
PYTHONPATH=sdk-python pytest agents/meta-agent/tests/test_baseline.py sdk-python/tests/eval/test_bridge_header.py -q
```

部署后对集群里的元智能体跑完全部基线。草稿和扩充用例走真实模型；拒绝类用例不调用模型。
