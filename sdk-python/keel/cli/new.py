"""Create a minimal offline agent project."""

import re
from pathlib import Path

from keel.manifest import load_manifest


TEMPLATES = ("chat-rag", "tool-agent", "graph-agent", "supervisor", "java-spring")


def template_dir(name: str) -> Path:
    root = Path(__file__).resolve().parents[3] / "templates" / name
    if not (root / "agent.yaml").is_file() and not any(root.glob("*.yaml")):
        raise FileNotFoundError(f"Template {name} is not installed")
    return root


def create_project(name: str, *, template: str = "hello-agent", root: str | Path = ".") -> Path:
    if not re.fullmatch(r"[a-z][a-z0-9-]{1,38}[a-z0-9]", name):
        raise ValueError("Agent name must match the keel/v1 metadata.name pattern")
    if template not in ("hello-agent", *TEMPLATES):
        raise ValueError(f"Unknown template {template}")
    target = Path(root) / name
    if target.exists():
        raise FileExistsError(f"{target} already exists")
    if template in TEMPLATES:
        _copy_template(template_dir(template), target, name)
        print(f"Created {target}. Run: cd {name} && keel dev")
        return target
    target.mkdir(parents=True)
    files = {
        "agent.yaml": ("apiVersion: keel/v1\nkind: Agent\nmetadata:\n"
                       f"  name: {name}\nspec:\n  runtime:\n"
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
                       "COPY . /app\nRUN pip install keel-sdk uvicorn\n"
                       "CMD [\"uvicorn\", \"app:app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]\n"),
        ".github/workflows/keel.yml": ("name: keel\non: [push, pull_request]\n"
                                       "jobs:\n  test:\n    runs-on: ubuntu-latest\n"
                                       "    steps:\n      - uses: actions/checkout@v4\n"
                                       "      - run: pip install keel-sdk pytest\n"
                                       "      - run: pytest -q\n"),
        ".gitignore": ".keel/\n__pycache__/\n.pytest_cache/\n",
    }
    for relative, content in files.items():
        path = target / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    load_manifest(target / "agent.yaml")
    print(f"Created {target}. Run: cd {name} && keel dev")
    return target


def _copy_template(source: Path, target: Path, name: str) -> None:
    second = f"{name}-aux"
    if not re.fullmatch(r"[a-z][a-z0-9-]{1,38}[a-z0-9]", second):
        raise ValueError("Agent name is too long for the second java-spring agent")
    for path in source.rglob("*"):
        if not path.is_file() or path.name == ".gitkeep":
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
