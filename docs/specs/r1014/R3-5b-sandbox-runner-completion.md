# R3-5b 沙箱 runner 完成与候选测试校验

## 目标

`DF-0002` 的 `SB-0005` 复现了真实故障：runner 在 1 秒内退出并写出测试报告，但同 Pod 的两个假服务仍运行，Job 一直是 Active，平台最终误报 10 分钟超时。报告还显示沙箱镜像缺 `pytest-asyncio`，导致四个异步测试无法执行。

## 范围

- `Fabric8SandboxCluster` 以 runner 容器退出码作为沙箱完成信号，读取同一 Pod 的 runner 日志；即使假服务仍运行，也按实际测试结果收尾并清理 Job。
- 沙箱镜像安装 `pytest-asyncio`，交付仓库的依赖锁文件也包含同版本插件，支持元智能体生成的异步 pytest 用例和后续 CI。
- 元智能体候选校验拒绝 Python 文件中误用 JSON 小写 `true`、`false`、`null`，模型在交付前重写。
- 旧候选快照可能含该错误；修复轮次只把旧快照当作只读输入，仍允许模型读取并修正，新候选继续严格校验。
- 保持 10 分钟时限、隔离配置、审批与门禁不变。

## 界面对齐

复用任务页现有 `sandboxAttempts.report` 和 `execution.error` 字段；无需改 Vue。`ui-checklist.md` 中 R1-7 仍按实际测试完成次数计轮次。

## 验收

- runner 退出 0 或非 0 时，分别产出 DONE/FAILED 和原始测试报告，不等待假服务退出。
- 镜像提供异步 pytest 插件；候选校验拒绝小写 JSON 字面量。
- 定向运行 keel-server、沙箱和元智能体测试，CI 构建部署后复测 `DF-0002`。
