# R1021 动态逻辑模型路由

## 目标与范围

实现用户确认的五项：逻辑模型规则增删改、完整路由表草稿/发布/回滚、发布前逐 Key 授权校验与一键补齐、近24小时历史请求回放、逻辑模型继承网关降级。对照 docs/console/Keel-优化效果-20261018.html 的路由表和回放试算，以及20261014控制台原型和 ui-checklist。

契约先扩充 contracts/console-api.openapi.yaml，新增 /insight/route-table GET/POST 及 /publish、/rollback、/grants、/replay POST。网关对应 /admin/v1/route-table。均使用显式 revision 乐观锁；写操作管理员且审计。规则包含逻辑名、环境（prod/staging，旧dev规则仅兼容读）、主/次模型、权重、降级策略、灰度比例及灰度模型、可选最小输入量。只接入已配置非embedding模型，不接收厂商密钥。

网关新增 RouteTable、RoutingJournal，RoutePolicy 持有相同Redis配置的表实例。Redis保存整表版本与共享草稿，CAS保存、发布和回滚，避免副本覆盖；回滚生成新版本恢复上一份完整快照。已有硬编码表仅作为首次引导种子。授权缺失或仍有Key引用的逻辑名删除阻止发布；补授权不轮换Key、不改预算和配额、不跨环境，保留已有渠道份额并补同环境渠道。发布和实际调用都校验具体目标授权；显式具体模型继续兼容原Key fallback，逻辑模型按表统一降级，不依赖manifest fallback。首次引导维持旧分流权重与环境，未配置原型所示长上下文/灰度条件不虚构已执行能力。

回放只使用真实记录的逻辑模型/请求ID/环境/输入长度，绝不保存输入原文；基于已发布与草稿同一选择函数计算分布、缺授权数和按真实账单token估算费用，不请求厂商、不扣预算。部署前没有逻辑名的历史账单明确计入不可回放，不猜测；样本窗口和记录上限明确展示。

修改范围：contracts、keel-llm路由/请求执行/管理接口及对应测试；keel-server集成客户端、InsightController及对应测试；console API生成类型、模型页新增独立LogicalRouting组件及测试；网关接口事实与技术文档、ui-checklist和验收证据。SDK/manifest保持兼容，不修改已有智能体配置。

## 验证与交付

本地测试覆盖草稿隔离、冲突、发布授权、逻辑引用删除、回滚、实际普通/流式降级、具体模型兼容、灰度边界、回放真实账单与无上游调用。前端类型检查/构建与既有模型页相关测试。推送main后等待CI/CD成功，真实账号验证页面操作和网关调用；使用独立QA逻辑名/Key，恢复临时配置并保留证据。

## 本地结果

网关全量71项，70通过、1项PG容器测试因本机无Docker跳过；独立Redis跨实例测试通过。服务端代理/费用/架构11项通过。模型页与导航12项通过，TypeScript和生产构建通过。真实Chromium使用本地接口夹具验证新增、再次编辑、保存和回放，无页面异常，截图见 docs/qa/evidence/2026-10-21-dynamic-routing/local-ui.png。部署及真实请求验收待完成。
