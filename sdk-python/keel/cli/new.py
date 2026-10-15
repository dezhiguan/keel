"""Create a minimal offline agent project."""

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from keel.manifest import load_manifest


TEMPLATES = ("loop-agent", "chat-rag", "tool-agent", "graph-agent", "supervisor", "java-spring")


def template_dir(name: str) -> Path:
    root = Path(os.environ["KEEL_TEMPLATES_DIR"]) / name if os.environ.get("KEEL_TEMPLATES_DIR") else Path(__file__).resolve().parents[3] / "templates" / name
    if not (root / "agent.yaml").is_file() and not any(root.glob("*.yaml")):
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
    wheel = _sdk_wheel() if template != "java-spring" else None
    if template in TEMPLATES:
        _copy_template(template_dir(template), target, name)
        if wheel is not None:
            _copy_wheel(wheel, target)
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
        "Dockerfile": ("FROM python:3.11-slim\nWORKDIR /app\n"
                       "COPY . /app\nRUN pip install --no-cache-dir /app/vendor/keel_sdk-*.whl uvicorn\n"
                       "CMD [\"uvicorn\", \"app:app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]\n"),
        ".github/workflows/keel.yml": ("name: keel\non: [push, pull_request]\n"
                                       "jobs:\n  test:\n    runs-on: ubuntu-latest\n"
                                       "    steps:\n      - uses: actions/checkout@v4\n"
                                       "      - run: pip install vendor/keel_sdk-*.whl pytest\n"
                                       "      - run: pytest -q\n"),
        ".gitignore": ".keel/\n__pycache__/\n.pytest_cache/\n",
    }
    for relative, content in files.items():
        path = target / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    _copy_wheel(wheel, target)
    load_manifest(target / "agent.yaml")
    print(f"Created {target}. Run: cd {name} && keel dev")
    return target


def _sdk_wheel() -> Path:
    configured = os.environ.get("KEEL_SDK_WHEEL")
    if configured:
        wheel = Path(configured).expanduser().resolve()
    else:
        repo = Path(__file__).resolve().parents[3]
        builder = repo / "scripts/build-template-wheel.sh"
        if not builder.is_file():
            raise FileNotFoundError("未找到 Keel SDK wheel；请设置 KEEL_SDK_WHEEL 指向本地构建的 wheel")
        env = os.environ.copy()
        env["KEEL_PYTHON_BIN"] = sys.executable
        built = subprocess.run(["bash", str(builder)], cwd=repo, env=env,
                               text=True, capture_output=True, check=True)
        wheel = Path(built.stdout.strip().splitlines()[-1])
    if not wheel.is_file() or not wheel.name.startswith("keel_sdk-") or wheel.suffix != ".whl":
        raise FileNotFoundError(f"无效的 Keel SDK wheel：{wheel}")
    return wheel


def _copy_wheel(wheel: Path, target: Path) -> None:
    vendor = target / "vendor"
    vendor.mkdir(parents=True, exist_ok=True)
    shutil.copy2(wheel, vendor / wheel.name)


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
