# 控制台双环境收敛验收

2026-10-21，用户54（官德志）真实认证控制台验证。

## 改动

- 有效配置环境仅测试staging和生产prod。顶栏为“全部环境 / prod / staging”。
- 创建表单默认staging，提示词编辑默认staging、预生产激活仅staging；渠道新增只允许staging/prod。
- 普通候选编辑仅staging；详情版本页不再把dev/test列为可编辑草稿。历史正式快照、来源环境、审计和追踪保留。
- 后端拒绝dev/test新登记、候选修改、提示词保存/激活和渠道新增；只读能力不因环境收敛获得prod权限。旧dev授权仅允许关闭。
- 保留v1线协议历史枚举解码、历史查询、现有凭证与底层路由兼容。未批量删库、吊销其他员工密钥或迁移其运行环境。旧来源为dev的正式版本仍可追溯，不能将其改写成staging来源。

环境改动提交9ae060615f09e4946c908af2e1baf0f04a0f8869。main并行合入b97f133预算表格布局后，原部署按并发策略取消，保留该布局改动。CI补齐SDK评测作业声明的[eval]可选依赖，提交f2e7d6886ca6a80ab7a4a6dc8b94d9ae0a2e2313；没有修改SDK业务行为或跳过测试。

## 测试

- 本地前端26文件196项通过，相关环境/向导/提示词13项通过，类型检查和生产构建成功。
- 本地后端环境拒绝、候选规则、提示词缓存5项通过。无Docker的本机未运行PG容器测试；GitHub完整CI通过，包含PG持久化用例。
- 原完整CI39483169931成功；最终CI39483909796成功。JSON证据见evidence/2026-10-21-two-environments/。
- 一次部署回归暴露Langfuse可选依赖缺失，失败原件保留；补齐依赖后元智能体闭环通过。首次浏览器脚本监听了错误的/insight/models地址，实际客户端使用/insight/costs；修正验收脚本后确认新建请求env=staging，没有改变产品默认值来迎合测试。

## 真实浏览器

browser-acceptance.json记录以下实际结果：

1. 顶栏恰好3个选项，staging/prod切换后实际列表请求正确且返回环境一致。
2. 新建默认调用staging逻辑路由；提示词无dev/test激活按钮。
3. dev/test各5种写入共10次均400拒绝，未创建测试智能体、提示词版本或模型渠道。
4. 历史查询仍200：dev有13条登记、test为0；版本页只显示staging候选，正式v1仍保留。
5. 通过真实模型和受控工具查询staging员工成功，Trace 55313196fe927850516f5786cd94f5c7，受管理answer v2、fallback=false。X-Keel-Eval-Run贯通，普通会话签名未改变。

截图creation.png、prompt.png、versions.png已保存。runtime.json记录新server/console各2/2，以及个人助理原70773443镜像两实例Ready、staging标签；助理镜像、SDK和凭证未更改。

## 部署

最终部署流水线39483909859为success，包含所有相关服务部署、冒烟和完整部署快照，结论和各作业状态见final-deployment.json。运行镜像使用f2e7d6886ca6标签；流水线执行现有全部相关测试、构建、滚动部署和冒烟。本次不发布个人助理prod，不改变其KB授权、低预算或MVP0.2发布状态。
