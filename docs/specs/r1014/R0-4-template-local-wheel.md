# R0-4 模板从仓库 wheel 安装 Python SDK

## 目标

四个 Python 模板及默认 `keel new` 项目的 Dockerfile 只从项目内 wheel 安装 `keel-sdk`，不向公网 PyPI 查找同名包；Java 模板仍可构建。

## 依赖

- 前置任务：无。
- 依赖的契约文件：无。
- 依赖的外部组件及其真实行为：Docker build context 只能读取本目录文件；`pip install /path/*.whl` 对 SDK 使用本地 wheel。其他 Python 运行依赖可由部署环境配置的镜像源安装；全离线镜像与锁定依赖属于 R2-2。

## 改哪些文件

```
docs/specs/r1014/R0-4-template-local-wheel.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
templates/{chat-rag,tool-agent,graph-agent,supervisor}/Dockerfile
templates/java-spring/pom.xml
templates/java-spring/Dockerfile
sdk-python/keel/cli/new.py
sdk-python/tests/templates/test_all_templates.py
sdk-python/tests/cli/test_cli.py
scripts/build-template-wheel.sh
sdk-python/pyproject.toml
```

## 接口契约

- 仓库内先运行 `scripts/build-template-wheel.sh`，在 `sdk-python/dist/` 生成当前源码的 `keel_sdk-*.whl`。
- `keel new` 复制该 wheel 到生成项目的 `vendor/`；也可通过 `KEEL_SDK_WHEEL` 指定已构建的本地 wheel。找不到 wheel 时明确失败，不生成构建时可能误装同名公网包的 Dockerfile。
- Python Dockerfile 将 `vendor/keel_sdk-*.whl` 随构建上下文复制到 `/app/vendor/`，再通过该路径安装 SDK；其他依赖按部署环境配置的镜像源安装。
- Java 模板 Maven 打包产生 `target/app.jar`，与 Dockerfile 的 `COPY` 路径一致。

## 界面对齐

无。`ui-checklist.md` 没有 R0-4 行；本任务只影响脚手架构建，不改控制台。

## 实现要点

- `keel new` 生成目录仍可离线完成；wheel 必须来自本地文件，复制二进制时不能按 UTF-8 文本读取。
- Java 模板不依赖 Python SDK；修正 Maven 产物名及 Spring Boot 可执行 jar 打包，使 Dockerfile 引用的文件确实存在。
- 本机环境按 `docs/TASKS.md` 不启动 Docker。用 SDK 模板测试和 Dockerfile 静态断言验收；Docker 离线构建步骤留给具备 Docker 的 CI。

## 验收标准

```bash
scripts/build-template-wheel.sh
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/templates/test_all_templates.py::test_all_template_dockerfiles_use_the_bundled_wheel tests/cli/test_cli.py::test_new_project_matches_schema_and_ignores_local_state tests/cli/test_cli.py::test_new_rejects_a_missing_local_sdk_wheel -q
cd <generated-java-spring-project> && mvn -o -DskipTests package
```

- [x] 四个 Python Dockerfile 与默认生成项目均无裸 `pip install keel-sdk`，SDK 来源为本地 wheel。
- [x] 五个模板项目均能生成；Java 模板产出可执行 `target/app.jar`，Dockerfile 无 Python SDK 安装。
- [ ] 五个模板的完整烟雾测试通过：本机沙箱阻止测试中的套接字绑定及 Mockito agent 自附着；针对性测试 3 个通过。
- [ ] 在有 Docker 的 CI 中，禁用公网 PyPI 后五个模板按各自运行依赖镜像源构建通过。

## 明确不做

- 不在 R0-4 发布 GitHub Release wheel（R2-1）或实现独立仓库的锁定依赖与 CI（R2-2）。
