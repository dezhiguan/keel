# R2-5 元智能体独立成库

## 目标

meta-agent 的镜像只以 `agents/meta-agent` 为构建上下文，SDK 用 wheel 安装，脚手架模板来自该 wheel，不再复制底座的 `sdk-python`、`contracts`、`templates`。底座另留一份不参与部署的示例。远程仓库 `dezhiguan/meta-agent` 本任务不创建、不推送。

## 依赖

- 前置任务：R2-2。`keel new` 已能生成独立项目；本任务让已安装的 SDK 自带模板，元智能体镜像才能离开底座源码树。
- 依赖的契约文件：无新字段。
- 依赖的外部组件及其真实行为：不调用 GitHub 创建仓库，不改线上部署。镜像标签和 `deploy/k3s/services/meta-agent.yaml` 的发布方式不变。

## 改哪些文件

```
docs/specs/r1014/R2-5-meta-agent-repo.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
sdk-python/pyproject.toml
sdk-python/keel/cli/new.py
scripts/build-template-wheel.sh
sdk-python/tests/release/test_install_keel_sdk.py
agents/meta-agent/Dockerfile
agents/meta-agent/README.md
agents/meta-agent/workflow.py
examples/meta-agent/README.md
examples/meta-agent/agent.yaml
deploy/docker/meta-agent.Dockerfile
.github/workflows/keel-cd.yml
ci/changed-services.sh
.gitignore
```

## 接口契约

- 仓库名是 `dezhiguan/meta-agent`，不在 `keel-agents` 组织下。
- 已安装的 `keel-sdk` 在 `keel/_templates/<模板名>` 带有脚手架。开发检出仍优先用底座的 `templates/`。
- 镜像构建上下文是 `agents/meta-agent`。工作流先把 `scripts/build-template-wheel.sh` 产出的 wheel 放到该目录的 `vendor/`，Dockerfile 只安装这个 wheel。`vendor/` 不进 git。

## 界面对齐

无。`ui-checklist.md` 没有 R2-5 行；本任务不改控制台。

## 实现要点

- `examples/meta-agent` 只说明独立仓库和一份不部署的 manifest。可运行代码仍在 `agents/meta-agent`，该目录就是未来仓库的根。
- 不把线上构建改成检出一个还不存在的远程仓库，否则下一次发布会失去元智能体镜像。
- 交付说明里不再要求 `pip install -e` 底座源码路径。

## 验收标准

```bash
KEEL_SDK_VERSION=1.0.0 scripts/build-template-wheel.sh
cd sdk-python && ../.venv/bin/python -m pytest tests/release/test_install_keel_sdk.py tests/cli/test_cli.py::test_new_project_matches_schema_and_ignores_local_state -q
```

- [x] wheel 内含 `keel/_templates/loop-agent/agent.yaml`。
- [x] `agents/meta-agent/Dockerfile` 与 `keel-cd.yml` 的构建上下文不复制 `sdk-python`、`contracts` 或 `templates`。
- [x] `examples/meta-agent` 不参与镜像和部署清单。

## 明确不做

- 不创建、不推送 `dezhiguan/meta-agent`。
- 不改 `deploy/k3s/services/meta-agent.yaml` 的运行配置，也不在 keel.ragforge.net 上发布。
- 不把元智能体源码从本仓库删掉。远程仓库就绪后，再把构建改为检出该仓库。
