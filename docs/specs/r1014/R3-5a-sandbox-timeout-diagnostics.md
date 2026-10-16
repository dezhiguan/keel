# R3-5a 研发沙箱超时诊断

## 目标

`DF-0002` 的候选 PR 已创建，但沙箱 `SB-0003`、`SB-0004` 均在 10 分钟后只显示“结果读取失败或超时”。运行器没有产出测试结论时，任务详情应显示 Pod 等待原因或运行器日志，供操作者修复部署故障后重试。

## 范围

- `Fabric8SandboxCluster` 从 Pod 状态读取容器等待原因、Pod 调度原因；运行器日志为空时用该诊断文本。
- `SandboxService` 在截止时先观察 Job，保留诊断，再按现有 504 `DEVFLOW_SANDBOX_TIMEOUT` 契约返回。
- `MetaAgentExecution` 把超时诊断写入现有 `execution.error` 与阶段记录；基础设施超时不计入三轮代码修正。
- 不改变沙箱隔离、权限、时限、门禁或审批规则。

## 界面对齐

现有任务详情 `console/src/views/jobs/JobDetail.vue` 已展示 `execution.error`，无需新增字段或布局。`ui-checklist.md` 中 R1-7 的修正轮次仍只统计真正完成测试的候选轮次。

## 验收

- 沙箱在截止前已经完成时，读回实际 PASS/FAIL，不误报超时。
- Pod 等待镜像或调度时，超时记录包含可读原因；报告仍限制 64KB。
- 任务详情显示该原因，且允许修复基础设施后重试。
- 只运行 keel-server 当前模块的测试。
