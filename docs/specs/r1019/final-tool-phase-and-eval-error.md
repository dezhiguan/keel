# R1019 最终答复阶段与评测失败恢复

正式评测 ev_1f96660302ce4eaf9c134153d5069307 未完整完成。链路c495dd5e784bdb15baba19036dc7970c：先DeepSeek目录补查，再Qwen在最终阶段返回工具调用，SDK按范围拒绝，未越权执行。当前请求虽然choice=none仍携带定义。按百炼官方文档可完全不传tools禁用工具；最终阶段同时移除定义并保留none、历史别名和拒绝执行检查。不得丢弃工具调用而伪装成功、增加补查次数或删除用例。验证实际wire和完整正式评测。参考https://help.aliyun.com/en/model-studio/qwen-function-calling 。

页面在新运行FAILED后只弹短暂toast，保留旧通过结果且显示发布入口（截图prod-eval-failed-old-pass.png），存在误读。新增持续失败信息、隐藏旧结果和发布入口；刷新也恢复最新失败任务。active接口新增可选includeFailed，默认false兼容旧调用；true查询最新任务，只有QUEUED/RUNNING/FAILED返回runId，最新DONE必须返回null，避免旧失败遮盖新成功。前端初始化恢复必须完成才能启用运行，防止恢复晚到覆盖新点击。新运行清除失败状态，成功后更新结果。覆盖失败刷新、失败后新成功和旧结果晚到竞态。
