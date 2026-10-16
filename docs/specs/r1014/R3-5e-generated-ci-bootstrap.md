# R3-5e 生成仓库 CI 启动依赖

## 目标

`DF-0002` 的第三轮候选通过沙箱且 GitHub Actions 已创建 Job，但安装 SDK 步骤失败。生成仓库没有 `KEEL_GITHUB_TOKEN` secret，底座仓库也没有 `sdk-v1.0.0` Release。工作流还依赖没有配置的 `KEEL_SERVER_URL` secret。

## 范围

- Python 模板及 `keel new` 的 Actions 工作流用内置 `github.token` 下载公开的底座 Release 和构建镜像，不向生成仓库复制平台 GitHub PAT。
- 门禁与 staging 发布从仓库变量 `KEEL_SERVER_URL` 读取地址；地址必须由仓库拥有者配置，SDK 不写默认主机。
- 发布 `sdk-v1.0.0` wheel 与 Java starter，核对生成仓库可下载。
- 旧候选 SHA 的沙箱结论不迁移到任何新提交。

## 界面对齐

没有新增控制台界面；任务页继续显示现有候选 SHA、沙箱轮次与 CI 状态。

## 验收

- 全部 Python 模板和 hello-agent 工作流使用内置 `github.token`，且 Gate 只读取 `vars.KEEL_SERVER_URL`。
- Release 附件能以生成仓库的 Actions 令牌下载。
- 对新的精确候选 SHA 重跑沙箱与 CI；缺失仓库变量时明确失败，不默默指向错误环境。
