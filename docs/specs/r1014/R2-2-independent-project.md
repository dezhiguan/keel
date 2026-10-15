# R2-2 独立项目模板

## 目标

`keel new` 生成的目录本身就是一个独立仓库：带锁定的直接依赖、一份契约文件、以及从单测到 staging 发布的 CI。生成结果里不出现底座源码路径。

## 依赖

- 前置任务：R2-1。Python SDK 和 Java starter 的安装方式沿用 R2-1，不改发布位置。
- 依赖的契约文件：生成时复制 `manifest.schema.json`、`sse-events.schema.json`、`invoke.openapi.yaml`、`error-codes.yaml`。不新增字段。
- 依赖的外部组件及其真实行为：`keel gate`、`keel register`、`keel release` 已由 CLI 提供。地址只读 `KEEL_SERVER_URL`，不写默认主机。本任务不连真实 keel-server。

## 改哪些文件

```
docs/specs/r1014/R2-2-independent-project.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
sdk-python/pyproject.toml
sdk-python/keel/cli/new.py
sdk-python/tests/cli/test_cli.py
sdk-python/tests/templates/test_all_templates.py
templates/loop-agent/Dockerfile
templates/loop-agent/requirements.lock
templates/loop-agent/.github/workflows/keel.yml
templates/chat-rag/Dockerfile
templates/chat-rag/requirements.lock
templates/chat-rag/.github/workflows/keel.yml
templates/chat-rag/tests/test_smoke.py
templates/tool-agent/Dockerfile
templates/tool-agent/requirements.lock
templates/tool-agent/.github/workflows/keel.yml
templates/graph-agent/Dockerfile
templates/graph-agent/requirements.lock
templates/graph-agent/requirements.txt
templates/graph-agent/.github/workflows/keel.yml
templates/supervisor/Dockerfile
templates/supervisor/requirements.lock
templates/supervisor/.github/workflows/keel.yml
templates/java-spring/.github/workflows/keel.yml
```

## 接口契约

- 生成项目包含 `contracts/` 下的四份契约，内容与底座 `contracts/` 中的同名文件一致。
- Python 直接依赖写在 `requirements.lock`，版本用 `==` 钉死。SDK 仍由 `scripts/install-keel-sdk.py` 从 Release 安装，不写进这个锁文件。
- CI 顺序：单测 → `keel gate --env staging` → 构建镜像 → 仅 `main` 推送时 `keel register --env staging` 和 `keel release --env staging`。
- Python 门禁前在 `127.0.0.1:8000` 起服务；Java 先打包，再用 `--server.port=8080` 起服务。这两处与模板 manifest 的 endpoint 一致。
- 工作流、Dockerfile、锁文件和复制进项目的契约里不出现 `sdk-python`、`templates/` 或本机绝对路径。

## 界面对齐

无。`ui-checklist.md` 没有 R2-2 行；本任务不改控制台。

## 实现要点

- 契约在 `keel new` 执行时复制进项目。安装后的 CLI 从 wheel 内的 `keel/_schemas` 复制；在仓库里开发时从底座 `contracts/` 复制。项目文件里不保留这个来源路径。
- Java 项目同样复制契约和安装脚本，供 CI 调用 `keel` 命令。镜像构建仍只复制 `target/app.jar`。
- 拉取请求跑单测、门禁和镜像构建。注册和发布只在推送到 `main` 时执行，避免每个拉取请求都写 staging。
- 直接依赖版本取本机已安装、且模板测试正在使用的版本：`httpx==0.28.1`、`pytest==9.1.1`、`uvicorn==0.54.0`；graph-agent 另加 `langgraph==1.2.12`、`langgraph-checkpoint-sqlite==3.1.1`。

## 验收标准

```bash
cd sdk-python && ../.venv/bin/python -m pytest tests/cli/test_cli.py tests/templates/test_all_templates.py -q
```

- [x] 生成项目含四份契约和 `==` 锁定的直接依赖。
- [x] CI 文本按单测、gate、构建镜像、register、release 的顺序出现。
- [x] 生成项目中没有底座源码路径。

## 明确不做

- 不推送生成的仓库，也不对真实 staging 执行注册。
- 不补 Git 服务写 workflow 的权限（R2-3）。
- 不实现 `KEEL_MODE=standalone`（R2-4）。
- 不把传递依赖全部钉进锁文件。SDK wheel 的依赖仍由安装该 wheel 时解析。
