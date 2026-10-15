# R2-1 发布到 GitHub

## 目标

契约 `keel/v1` 的可安装版本是 `1.x.y`：打 tag `sdk-v1.x.y` 后，CI 把 Python wheel 挂到该 GitHub Release，并把 Java starter 发到 GitHub Packages。`keel new` 生成的项目用访问令牌安装这两处，不再把 wheel 打进仓库，也不按包名 `keel-sdk` 去公网 PyPI 查找。

## 依赖

- 前置任务：R0-4。R0-4 的仓库内 wheel 只作为发布前的过渡，本任务替换安装来源。
- 依赖的契约文件：无新字段。发布版本线对应已冻结的 `keel/v1`（tag `contracts/v1.0.0`），第一版为 `1.0.0`。
- 依赖的外部组件及其真实行为：仓库是 `dezhiguan/keel`。Python 用 GitHub Release 附件，因为 GitHub Packages 不支持 PyPI。私有 Release 的附件用 `GET /repos/{owner}/{repo}/releases/tags/{tag}` 找到同名 wheel，再以 `Accept: application/octet-stream` 下载附件 API 地址。Java 用 `https://maven.pkg.github.com/dezhiguan/keel`，Maven server id 为 `github`。本任务不推 tag，也不创建远程 Release。

## 改哪些文件

```
docs/specs/r1014/R2-1-github-release.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
.github/workflows/release-sdk.yml
pom.xml
scripts/build-template-wheel.sh
sdk-python/keel/cli/install_keel_sdk.py
sdk-python/keel/cli/new.py
sdk-python/tests/cli/test_cli.py
sdk-python/tests/templates/test_all_templates.py
sdk-python/tests/release/test_install_keel_sdk.py
templates/loop-agent/Dockerfile
templates/loop-agent/.github/workflows/keel.yml
templates/chat-rag/Dockerfile
templates/chat-rag/.github/workflows/keel.yml
templates/tool-agent/Dockerfile
templates/tool-agent/.github/workflows/keel.yml
templates/graph-agent/Dockerfile
templates/graph-agent/.github/workflows/keel.yml
templates/supervisor/Dockerfile
templates/supervisor/.github/workflows/keel.yml
templates/java-spring/pom.xml
templates/java-spring/ci/settings.xml
templates/java-spring/.github/workflows/keel.yml
```

## 接口契约

- tag `sdk-v1.x.y` 才发布，版本号是去掉前缀后的 `1.x.y`。`sdk-v0.y.z`、`sdk-v2.0.0`、无 `sdk-v` 前缀的 tag 不发布。
- wheel 文件名 `keel_sdk-1.x.y-py3-none-any.whl`。源码里的开发版本可以仍是 `0.1.0`；发布时用 `KEEL_SDK_VERSION` 写入 wheel 元数据。
- Maven 只发布 `keel-parent`、`keel-common`、`keel-spring-boot-starter`。不发布 gateway、server、audit、llm。
- Python 项目用 `scripts/install-keel-sdk.py` 下载上述 wheel 再 `pip install` 该文件。令牌只来自环境变量 `KEEL_GITHUB_TOKEN` 或 Docker BuildKit secret `KEEL_GITHUB_TOKEN`。
- Java 模板依赖 `com.keel:keel-spring-boot-starter:1.0.0`，仓库地址是 GitHub Packages。`ci/settings.xml` 的密码是 `${env.KEEL_GITHUB_TOKEN}`。

## 界面对齐

无。`ui-checklist.md` 没有 R2-1 行；本任务不改控制台。

## 实现要点

- 安装器不 import `keel`，这样生成的项目在装上 SDK 之前就能运行它。
- `pip` 的参数是 wheel 文件路径。不要出现包名安装 `pip install keel-sdk`，避免命中公网同名包。wheel 自己的依赖仍可走环境里配置的索引。
- 令牌不进镜像层、不进仓库。Docker 构建用 secret mount。
- 底座各模块的 `0.1.0-SNAPSHOT` 不在本任务改写。发布工作流只在 CI 工作区把将要上传的构件改成 tag 上的版本。
- 本机没有 Docker。用假的 GitHub API 验证下载，不创建远程 Release。

## 验收标准

```bash
cd sdk-python && ../.venv/bin/python -m pytest tests/release/test_install_keel_sdk.py tests/cli/test_cli.py tests/templates/test_all_templates.py -q
KEEL_SDK_VERSION=1.0.0 scripts/build-template-wheel.sh
```

- [x] tag 不是 `sdk-v1.x.y` 时发布检查失败。
- [x] 安装器只下载 Release 上的 wheel 文件；请求不到公网 PyPI，也没有包名 `keel-sdk`。
- [x] 生成的 Python 项目不再携带 `vendor/*.whl`；Java 模板指向 GitHub Packages 上的 `1.0.0`。
- [x] 发布工作流只部署 parent、common、starter。

## 明确不做

- 不推送 tag，不调用 GitHub 创建 Release 或上传 Maven 构件。第一版发布由维护者推送 `sdk-v1.0.0` 触发。
- 不生成锁定依赖、完整独立仓库 CI 和 staging 注册（R2-2）。
- 不改 Git 服务的 workflow 权限（R2-3）。
