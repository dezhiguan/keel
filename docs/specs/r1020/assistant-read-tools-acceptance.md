# 个人助理受控只读接入验收

2026-10-21，独立分支 feat/assistant-readonly-v02。同一 keel-assistant，仅 dev/staging。平台代码与助理业务分仓提交。

实际运行 Java 镜像源44b590f056df3665c72d60f4c35fd13299875241；后续e4bad81aa2535ce74b8c717ca1c920b06d0fe3b9只修订生成类型、迁移契约测试和CI覆盖。专项39项通过，完整CI 39473034646成功。运行镜像digest sha256:184066eb53e8d022953852824314d8733348239c766baac8bbe356529971151a，两平台Pod Ready。

真实控制台已登记/发布并显式授权3个受控工具：keel.employees.list、keel.employees.detail、keel.knowledge.authorize，工具v2 ONLINE；本助理平台组dev/staging目标范围已显式批准并审计。普通candidate接口仍禁止任意扩展工具/KB权限。无管理员Cookie或长期令牌进入助理运行时；业务调用使用SDK服务身份及平台签发短期用户上下文。

真实验收包括分页、详情、环境纠正、对象纠正、混合请求只读部分、写操作拒绝；缺失和伪造用户票据均被真实调用端拒绝。只读集成9项中8项通过，知识库范围尚未获用户确认而拒绝，没有发放KB凭证。正式聊天评测ev_ff404b16632e44e5bab802b5d1c3d784为10/10。没有据此宣称完整0.2发布成功。

SSE实际中断：首token后定位并终止实际模型输出进程（先确认真实网关usage结算），控制台返回SERVER_INTERNAL_ERROR，非空trace/run标识、无final，逐帧通过contracts/sse-events.schema.json。剩余单实例使用PG会话正确查询纠正后的员工；恢复两Ready后统一Service命中两实例，新实例继续相同PG历史。原几次未真正中断的尝试保留、不计通过。

详细原始证据位于独立仓库dezhiguan/keel-assistant的docs/evidence/v0.2，汇总docs/acceptance-v0.2.md。真实知识检索、恶意文档、完整双用户与部分上游故障仍未验证。平台历史v1快照、旧平台镜像ca94d275fd1d、助理0.1镜像和prompt v1保留。prod、共享模型路由、全局预算与其他智能体凭证未改。

工作区并行提交5087af8的ingress SSE关闭缓冲修复已保留，线上浏览器实测逐段到达；不将该提交归入助理业务实现。
