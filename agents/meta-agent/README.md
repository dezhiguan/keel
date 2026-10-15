# meta-agent

这个目录是独立仓库 `dezhiguan/meta-agent` 的根。它不在 `keel-agents` 组织下，研发员工没有写权限。

镜像只使用本目录作为构建上下文。CI 先构建 `keel-sdk` wheel，放到 `vendor/`（不提交），Dockerfile 安装该 wheel。脚手架模板在 wheel 的 `keel/_templates` 里，不从底座源码树复制。

本任务不创建、不推送远程仓库。远程仓库建好之前，底座的发布流程仍用这个目录构建同一份镜像。
