# 元智能体护栏开启验收

## 授权与页面操作

用户授权使用guandezhi账号开启此前建议的间接注入和canary。通过正常登录页面登录，确认userId=54、ADMIN、非只读，页面显示官德志。没有将密码或认证令牌写入项目及验收证据。

先复制v2完整55条ACTIVE用例到`meta-agent/v3-guardrails`，再创建未发布v0候选，避免护栏保存接口更新已发布v2。复制后input/expectedOutput/metadata逐项规范化比较完全相等，定义哈希`70dc857f72385d4b80ac870a4db60fa60383a7009d7f31849bc06d67abcb6cc4`。

实际在页面点击间接注入enforce、canary enforce和保存；注册中心读回inputInjection=enforce、indirectInjection=enforce、taintedTool=approve、canary=enforce、pii=off。候选hash为`a2db03c59b384d78d6f8b5959a2746fd1b4aef4b0d48b16f655b48c9d90ff820`。

将上述策略与题集同步到手写项目agent.yaml，保留负责人源文件中的官德志更新。候选继承注册中心既有metadata.owner（研发效能组 / amy），不借本次护栏操作变更组织授权；该展示元数据与此前负责人源码更新的差异单独保留，不覆盖已发布快照。

## 预检

启用canary后，三条既有测试因固定读取messages[0]失效；改为按业务内容定位消息，保留原反馈、前版草稿、需求内容断言，并增加真实SDK发送了canary system消息的断言。不改业务逻辑或评分器。

完整定向验证包括元智能体及SDK竞争、恢复追踪、技能、评测、护栏测试。部署、实例实测、真实模型标记注入、55条门禁及发布结果待补充。
