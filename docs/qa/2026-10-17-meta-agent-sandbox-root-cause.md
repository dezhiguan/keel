# DF-0002 沙箱根因与修复

2026-10-17 使用管理员会话重试 `DF-0002`，产生 `SB-0005`。线上 `keel-server` 当时仍为镜像 `84e953b4bc7e`；此前提交 `9d6ec65` 已在远端 `main`，但没有 Actions 运行记录，尚未部署。

在 `keel-devflow-sandbox` 直接观察到 Pod `sb-0005-t8btc`：runner 启动后 1 秒以退出码 1 终止，并打印 `sha=94d036cd3364db655fecaa02a3d3765c66c2f10d snapshot=ok tests=FAIL`。四个异步测试均因缺少 `pytest-asyncio` 无法运行，另两个同步测试通过。两个假服务容器持续运行，Job 的 `status.active=1`，因此平台一直判为 RUNNING，最终按 10 分钟超时处理。这解释了 `SB-0003`、`SB-0004` 的现象。

本次代码让平台以 runner 退出状态收尾，沙箱镜像与交付仓库依赖锁文件增加 `pytest-asyncio`，元智能体交付前拒绝 Python 中误用的 JSON 小写字面量。Java 定向测试通过；本机沙箱禁止绑定本地端口，使 Python HTTP 集成测试无法完整运行，须以远端 CI 为准。部署后需再次重试并核对沙箱报告、自动修复轮次与 PR 状态。

私有仓库分支保护能力仍不可用；H4 必须继续阻止无保护的合并与发布。
