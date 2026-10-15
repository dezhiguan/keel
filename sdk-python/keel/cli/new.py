"""Create a minimal offline agent project."""

import json
import os
import re
import shutil
from pathlib import Path

from keel.manifest import load_manifest


TEMPLATES = ("loop-agent", "chat-rag", "tool-agent", "graph-agent", "supervisor", "java-spring")
CONTRACTS = ("manifest.schema.json", "sse-events.schema.json", "invoke.openapi.yaml", "error-codes.yaml")
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
        _write_sdk_installer(target)
        _copy_contracts(target)
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
        ".gitignore": ".keel/\n__pycache__/\n.pytest_cache/\n",
    }
    for relative, content in files.items():
        path = target / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    _write_sdk_installer(target)
    _copy_contracts(target)
    load_manifest(target / "agent.yaml")
    print(f"Created {target}. Run: cd {name} && keel dev")
    return target


def _python_dockerfile() -> str:
    return (
        "FROM python:3.11-slim\nWORKDIR /app\nCOPY . /app\n"
        "# docker build --secret id=KEEL_GITHUB_TOKEN,env=KEEL_GITHUB_TOKEN .\n"
        "RUN --mount=type=secret,id=KEEL_GITHUB_TOKEN \\\n"
        "    KEEL_GITHUB_TOKEN=\"$(cat /run/secrets/KEEL_GITHUB_TOKEN)\" \\\n"
        "    python scripts/install-keel-sdk.py \\\n"
        " && pip install --no-cache-dir -r requirements.lock\n"
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
      - name: Install keel-sdk from the GitHub Release
        env:
          KEEL_GITHUB_TOKEN: ${{ secrets.KEEL_GITHUB_TOKEN }}
        run: python scripts/install-keel-sdk.py
      - name: Install locked dependencies
        run: pip install -r requirements.lock
      - name: Unit tests
        run: pytest -q
      - name: Gate
        env:
          KEEL_SERVER_URL: ${{ secrets.KEEL_SERVER_URL }}
        run: |
          uvicorn app:app --host 127.0.0.1 --port 8000 &
          python -c 'import socket,time
for _ in range(50):
    try:
        socket.create_connection(("127.0.0.1", 8000), 1).close(); break
    except OSError:
        time.sleep(0.2)
else:
    raise SystemExit("agent did not open port 8000")'
          keel gate --env staging
      - name: Build the image
        env:
          KEEL_GITHUB_TOKEN: ${{ secrets.KEEL_GITHUB_TOKEN }}
        run: |
          image="ghcr.io/${GITHUB_REPOSITORY,,}:${GITHUB_SHA}"
          docker build --secret id=KEEL_GITHUB_TOKEN,env=KEEL_GITHUB_TOKEN -t "$image" .
          echo "image=$image" >> "$GITHUB_ENV"
      - name: Register and release staging
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        env:
          KEEL_SERVER_URL: ${{ secrets.KEEL_SERVER_URL }}
        run: |
          keel register --env staging
          keel release --env staging --image "$image"
"""


def _contract_dir() -> Path:
    packaged = Path(__file__).resolve().parents[1] / "_schemas"
    if all((packaged / name).is_file() for name in CONTRACTS):
        return packaged
    repo = Path(__file__).resolve().parents[3] / "contracts"
    if all((repo / name).is_file() for name in CONTRACTS):
        return repo
    raise FileNotFoundError("找不到契约文件，无法生成独立项目")


def _copy_contracts(target: Path) -> None:
    source = _contract_dir()
    destination = target / "contracts"
    destination.mkdir(parents=True, exist_ok=True)
    for name in CONTRACTS:
        shutil.copy2(source / name, destination / name)


def _write_sdk_installer(target: Path) -> None:
    source = Path(__file__).with_name("install_keel_sdk.py")
    destination = target / "scripts" / "install-keel-sdk.py"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


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
