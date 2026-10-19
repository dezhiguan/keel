# R1019 调试及控制入口去除展示证据阻塞

最终真实调试入口ready耗时tools1978ms、guard4360ms、memory24215ms、routing37075ms。浏览器资源计时定位routing GET /agents/{name} 25095ms、GET sessions 11744ms，均调用含外部链路补证的完整detail。

复用已验证的registeredDetail，仅从PG读取登记、实例及资源状态。会话、真实调用、工具授权和自检控制入口改用该读法，所有原权限、资源ACTIVE、状态、类型、env和自检规则不改。GET agent详情新增可选includeRuntimeEvidence（默认true兼容），调试页传false；完整抽屉仍默认读取运行证据。跳过运行证据时不显示伪造的已验证元素；调试页只消费登记状态、交互方式和环境版本。

先更新可选query契约再更新Java/TS。PG隔离观察不可用测试保留，聊天拒绝下线/失活资源/错误环境及会话隔离测试继续执行。部署后四个页面重新计时、真实对话和SSE/记忆抽样，门禁及发布绑定的SDK镜像保持ec8。
