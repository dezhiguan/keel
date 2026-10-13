"""Prototype roles that the existing meta-agent may produce; behavior code is model-authored."""

ROLES = {
    "dev-lead": ("supervisor", "编排业务员工生产任务；只委派已授权的研发员工，记录预算、阶段、失败轮次与人工关口；自己不写代码。"),
    "spec-agent": ("graph-agent", "把需求表单变成结构化需求单、岗位说明书草稿和授权清单；只从注册表选工具，风险等级不可降低。"),
    "dev-agent": ("graph-agent", "从确认需求生成实际业务代码和测试候选；调用沙箱验证，再经 Git 工具推送 PR；人的提交优先，冲突时停下。"),
    "eval-agent": ("tool-agent", "在人工种子用例上扩充边界、对抗和注入用例，起草评分函数；不得修改人工原例或读取隐藏考题。"),
    "code-review": ("tool-agent", "读取 PR diff 并评审模型调用、审计白名单、高风险工具审批及针对可见用例的特判。"),
    "ci-doctor": ("tool-agent", "读取 CI 日志和门禁报告，按维度诊断失败原因并输出可执行修复清单。"),
    "release-agent": ("tool-agent", "核查 staging 门禁和隐藏考题聚合结果；证据齐备才申请合并审批，批准前不得合并或发布。"),
}
