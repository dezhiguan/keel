"""Create a minimal offline agent project."""

import json
import os
import re
import shutil
from pathlib import Path

from keel.manifest import load_manifest


TEMPLATES = ("loop-agent", "chat-rag", "tool-agent", "graph-agent", "supervisor", "java-spring")
PYTHON_LOCK = "httpx==0.28.1\npytest==9.1.1\nuvicorn==0.54.0\n"


def _is_template(root: Path) -> bool:
    return (root / "agent.yaml").is_file() or any(root.glob("*.yaml"))


def template_dir(name: str) -> Path:
    configured = os.environ.get("KEEL_TEMPLATES_DIR", "").strip()
    if configured:
        root = Path(configured) / name
        if _is_template(root):
            return root
    checkout = Path(__file__).resolve().parents[3] / "templates" / name
    packaged = Path(__file__).resolve().parents[1] / "_templates" / name
    root = checkout if _is_template(checkout) else packaged
    if not _is_template(root):
        raise FileNotFoundError(f"Template {name} is not installed")
    return root


def create_from_spec(spec_file: str | Path, *, root: str | Path = ".") -> Path:
    spec_path = Path(spec_file)
    if not spec_path.is_file():
        raise FileNotFoundError(f"找不到需求单 {spec_path}")
    manifest_path = spec_path.with_name("agent.yaml")
    if not manifest_path.is_file():
        raise FileNotFoundError("需求单旁边要有 agent.yaml")
    sheet = json.loads(spec_path.read_text(encoding="utf-8"))
    if not isinstance(sheet, dict) or not str(sheet.get("title") or "").strip():
        raise ValueError("需求单要有标题")
    loaded = load_manifest(manifest_path)
    language = getattr(loaded.spec.runtime, "language", None)
    template = "java-spring" if str(language or "") == "java" else "tool-agent"
    project = create_project(loaded.metadata.name, template=template, root=root)
    (project / "agent.yaml").write_text(manifest_path.read_text(encoding="utf-8"), encoding="utf-8")
    (project / "spec.json").write_text(spec_path.read_text(encoding="utf-8"), encoding="utf-8")
    load_manifest(project / "agent.yaml")
    return project


def create_project(name: str, *, template: str = "loop-agent", root: str | Path = ".") -> Path:
    if not re.fullmatch(r"[a-z][a-z0-9-]{1,38}[a-z0-9]", name):
        raise ValueError("Agent name must match the keel/v1 metadata.name pattern")
    if template not in ("hello-agent", *TEMPLATES):
        raise ValueError(f"Unknown template {template}")
    target = Path(root) / name
    if target.exists():
        raise FileExistsError(f"{target} already exists")
    if template in TEMPLATES:
        _copy_template(template_dir(template), target, name)
        _finish_project(target, name, template)
        print(f"Created {target}. Run: cd {name} && keel dev")
        return target
    target.mkdir(parents=True)
    files = {
        "agent.yaml": ("apiVersion: keel/v1\nkind: Agent\nmetadata:\n"
                       f"  name: {name}\nspec:\n  kind: workflow\n  runtime:\n"
                       "    endpoint: http://127.0.0.1:8000\n"),
        "app.py": ("from keel import Agent\n\n"
                   "agent = Agent.from_manifest('agent.yaml')\n\n"
                   "@agent.entry\nasync def run(request, ctx):\n"
                   "    ctx.step('greet')\n"
                   "    return ctx.final('Hello from " + name + "')\n\n"
                   "app = agent.asgi()\n"),
        "tools/.gitkeep": "",
        "prompts/.gitkeep": "",
        "evals/seed.jsonl": "",
        "evals/scorers.py": "# Add deterministic scorers here.\n",
        "tests/.gitkeep": "",
        "Dockerfile": _python_dockerfile(),
        ".github/workflows/keel.yml": _python_workflow(),
        "requirements.lock": PYTHON_LOCK,
        ".gitignore": ".keel/\n__pycache__/\n.pytest_cache/\nvendor/\n",
    }
    for relative, content in files.items():
        path = target / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    _finish_project(target, name, "hello-agent")
    load_manifest(target / "agent.yaml")
    print(f"Created {target}. Run: cd {name} && keel dev")
    return target


def _python_dockerfile() -> str:
    return (
        "FROM python:3.11-slim\nWORKDIR /app\nCOPY . /app\n"
        "RUN wheel=\"$(find /app/vendor -name 'keel_sdk-*.whl' -print -quit)\" \\\n"
        " && test -n \"$wheel\" \\\n"
        " && pip install --no-cache-dir \"$wheel\" -r requirements.lock \\\n"
        " && rm -rf /app/vendor\n"
        "CMD [\"uvicorn\", \"app:app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]\n"
    )


def _python_workflow() -> str:
    return """name: keel
on: [push, pull_request]
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.11"
      - name: Download the pinned Keel SDK wheel
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          test -n "$GH_TOKEN"
          version="$(cat .keel-sdk-version)"
          mkdir -p vendor
          gh release download "sdk-v${version}" --repo dezhiguan/keel --pattern "keel_sdk-${version}-py3-none-any.whl" --dir vendor
          python -m pip install "vendor/keel_sdk-${version}-py3-none-any.whl"
      - name: Install locked dependencies
        run: pip install -r requirements.lock
      - name: Unit tests
        run: python -m pytest -q
      - name: Gate
        env:
          KEEL_SERVER_URL: ${{ vars.KEEL_SERVER_URL }}
        run: |
          uvicorn app:app --host 127.0.0.1 --port 8000 &
          for attempt in $(seq 1 50); do
            if python -c 'import socket; socket.create_connection(("127.0.0.1", 8000), 1).close()'; then break; fi
            sleep 0.2
          done
          python -c 'import socket; socket.create_connection(("127.0.0.1", 8000), 1).close()'
          keel gate --env staging
      - name: Build the image
        run: |
          image="ghcr.io/${GITHUB_REPOSITORY,,}:${GITHUB_SHA}"
          docker build -t "$image" .
          echo "image=$image" >> "$GITHUB_ENV"
      - name: Register and release staging
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        env:
          KEEL_SERVER_URL: ${{ vars.KEEL_SERVER_URL }}
        run: |
          keel register --env staging
          keel release --env staging --image "$image"
"""


def _finish_project(target: Path, name: str, template: str) -> None:
    """Add the files that make a generated directory a standalone project."""
    (target / ".keel-sdk-version").write_text("1.0.33\n" if template != 'java-spring' else "1.0.0\n", encoding="utf-8")
    (target / "COST-CONTROL.md").write_text(
        "# 成本控制接入\n\nPython 标准 SDK 自动传递根任务与后台费用上下文，策略由平台管理；"
        "必须通过平台身份、正式网关和兼容运行时部署后才具备权威预算控制。"
        "无服务身份时显示 not-connected，不把未覆盖费用当零。Java/第三方运行时需独立适配验收，不能宣称已覆盖。"
        "记忆与检索优化按业务验证，不继承其他助理的固定窗口、费用或维护批次。\n", encoding='utf-8')
    if template == "java-spring":
        setup = (
            "Maven 从 GitHub Packages 获取 `keel-spring-boot-starter`。设置 GitHub Packages 凭证后"
            "运行 `mvn -s ci/settings.xml test` 和 `mvn -s ci/settings.xml package`。"
            "CI 为门禁命令另外下载 `.keel-sdk-version` 指定的 Python CLI wheel。\n"
        )
    else:
        setup = (
            "本地已登录 GitHub CLI 后运行：\n\n"
            "```bash\n"
            "version=$(cat .keel-sdk-version)\n"
            "mkdir -p vendor\n"
            "gh release download \"sdk-v${version}\" --repo dezhiguan/keel --pattern \"keel_sdk-${version}-py3-none-any.whl\" --dir vendor\n"
            "python -m pip install vendor/keel_sdk-*.whl -r requirements.lock\n"
            "python -m pytest -q\n"
            "keel dev\n"
            "```\n"
        )
    (target / "README.md").write_text(
        f"# {name}\n\n"
        f"独立的 Keel 智能体项目，基于 `{template}` 模板。此目录就是项目根目录，"
        "可单独初始化 Git 仓库；不需要检出 Keel 底座源码。\n\n"
        "## 开发\n\n"
        "修改 `agent.yaml`、业务入口、`tools/`、`prompts/` 和 `evals/`。"
        "提交前运行项目测试，发布前运行 `keel gate --env staging`。\n\n"
        + setup + "\n"
        "`evals/` 保存评测样例与评分器，`.github/workflows/keel.yml` 定义项目自己的流水线。"
        "SDK 提供运行时、契约校验和平台客户端；项目只保存自己的业务代码。\n",
        encoding="utf-8",
    )
    (target / "AGENTS.md").write_text(
        f"# {name} 开发说明\n\n"
        "这是一个独立智能体项目。先阅读 `README.md`、`agent.yaml`、现有提示词和评测样例。\n"
        "业务逻辑写在项目入口及 `tools/`；平台运行时通过版本化 `keel-sdk` 引入。\n"
        "改动行为时同步更新评测与测试；提交前运行本项目测试。\n"
        "不要把 Keel 底座源码、契约或密钥复制进本项目。\n",
        encoding="utf-8",
    )
    ignore = target / ".gitignore"
    if "vendor/" not in ignore.read_text(encoding="utf-8").splitlines():
        with ignore.open("a", encoding="utf-8") as output:
            output.write("vendor/\n")
    if template != "java-spring":
        (target / "pyproject.toml").write_text(
            "[project]\n"
            f'name = "{name}"\nversion = "0.1.0"\n'
            'description = "Independent Keel agent project"\n'
            'requires-python = ">=3.11"\n\n'
            "[tool.pytest.ini_options]\ntestpaths = [\"tests\"]\n",
            encoding="utf-8",
        )


def _copy_template(source: Path, target: Path, name: str) -> None:
    second = f"{name}-aux"
    if not re.fullmatch(r"[a-z][a-z0-9-]{1,38}[a-z0-9]", second):
        raise ValueError("Agent name is too long for the second java-spring agent")
    skipped = {".keel", "__pycache__", ".pytest_cache", ".git"}
    for path in source.rglob("*"):
        if not path.is_file() or path.name == ".gitkeep" or skipped.intersection(path.parts):
            continue
        relative = Path(str(path.relative_to(source)).replace("{{name_aux}}", second).replace("{{name}}", name))
        text = path.read_text(encoding="utf-8").replace("{{name_aux}}", second).replace("{{name}}", name)
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text, encoding="utf-8")
    for manifest in target.rglob("*.yaml"):
        if manifest.parts[-2:] == (".github",) or ".github" in manifest.parts:
            continue
        content = manifest.read_text(encoding="utf-8")
        if content.startswith("apiVersion:"):
            load_manifest(manifest)
