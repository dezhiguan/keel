# `keel new` 生成独立智能体项目

## 目标

新生成的目录直接作为一个可独立开发、测试、构建和发布的智能体项目，
人工或编码智能体都能在该目录继续开发。现有智能体和线上部署不迁移。

## 项目边界

- 根目录有 README、AGENTS.md、项目元数据、manifest、智能体入口、提示词、工具、评测、测试、Dockerfile 和 CI。
- 项目只依赖GitHub Release 中已发布的 Keel SDK。构建时下载 SDK wheel；不复制底座的 `sdk-python`、`contracts`、`templates` 或其他服务代码。
- 生成代码不依赖 Keel 源码仓库的相对路径。每个项目可以单独初始化 Git 仓库。
- 生成器支持现有 Python 模板和 Java 模板；从需求单生成时保留需求单和确认过的 manifest。
- 控制台“由智能体生产”的元智能体交付路径也使用该生成器，不再额外塞入 manifest schema 副本，且保留生成项目的 README 与 AGENTS.md。

## 验收

- 所有模板生成后文件齐全，manifest 合法，现有烟测通过。
- 生成项目中没有底座源码/契约副本，也不从公网 PyPI 按包名安装 SDK。
- CI 先运行测试和门禁，再构建和发布；Docker 只安装预先下载的 SDK wheel。
- 改动不触碰现有 `agents/` 目录及其部署流水线。
