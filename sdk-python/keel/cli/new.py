"""Create a minimal offline agent project."""

import re
from pathlib import Path

from keel.manifest import load_manifest


def create_project(name: str, *, template: str = "hello-agent", root: str | Path = ".") -> Path:
    if not re.fullmatch(r"[a-z][a-z0-9-]{1,38}[a-z0-9]", name):
        raise ValueError("Agent name must match the keel/v1 metadata.name pattern")
    if template != "hello-agent":
        raise ValueError(f"Template {template} is provided by P0-13 and is not installed yet")
    target = Path(root) / name
    if target.exists():
        raise FileExistsError(f"{target} already exists")
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
