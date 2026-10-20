# Key 列表合并与配额弹窗日预算

## 范围
- 修改 `console/src/views/models/ModelGateway.vue`；更新本 spec 和 `docs/specs/r1014/ui-checklist.md`。
- 沿用 20261014 控制台原型的日预算金额、预留与进度条，以及 10–200 元、步长 5 元的预算滑块。
- 不变更接口契约，使用已有 budget 接口的 dailyBudgetCny、channels、tpm、concurrency。

## 行为
- Key 列表每行显示智能体、环境、Key 别名和版本、允许渠道、路由覆盖、RPM / TPM / 并发、已结算 + 预留 / 日预算、配额按钮。
- 保持横向表格，长别名单行，窄屏横向滚动；不显示当前可用。按渠道限制 RPM 时写“按渠道”，渠道旁保留具体 RPM。
- 日预算进度按（已结算 + 预留）/ 日预算计算，80% 告警，100% 红色；缺失金额显示未知，不伪造零。
- 弹窗直接显示“日预算：¥95（今日已用 ¥31.20）”和滑块，无调整/收起按钮、无独立预留行。
- 统一保存预算和限额；未编辑渠道时不提交 channels，保留原有授权和无限额设置，避免仅改预算强制分配 RPM。
- 沿用管理权限和 BLOCKED Key 限制；保存成功刷新数据，记录现有 config.change 审计。

## 验收
- 前端类型检查、构建、现有模型相关测试及前端门禁通过。
- 浏览器检查合并列、预算进度、滑块文本联动与保存请求；只改预算不重置渠道。
- 推送 main 后等待控制台部署和冒烟成功，线上复测并还原验证时调整的预算。

## 验证记录（2026-10-21）
- 实现提交：`c8596e0`，已推送 main。
- `npm run build`、完整 `npm test`（26 文件 / 196 测试）、`bash ci/frontend-gate.sh`、`git diff --check` 通过。
- [部署流水线](https://github.com/dezhiguan/keel/actions/runs/39471858912) 完成成功，控制台部署及冒烟通过；集群控制台镜像为 `c8596e0e9684`，2 个副本就绪。
- 部署后 `/models` 返回 200，入口引用本次构建的 `index-BjfInZPd.js`。
- 03:02 使用独立 Playwright 会话完成线上页面验收：`echo-dev` 滑块从 ¥30 调到 ¥35，保存返回 200；刷新并重新打开弹窗仍为 ¥35，再经页面恢复为 ¥30。只提交 dailyBudgetCny、tpm、concurrency，未提交 channels；模型授权、渠道份额与 Key 版本不变。
- 首次保存后立即读取另一个副本的成本查询仍可能短暂返回旧预算，页面刷新后读到新值；本次未改动后端缓存机制。测试证据：本地 `/tmp/key-budget-save-evidence.json`。
