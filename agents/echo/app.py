"""Real echo agent: one user turn goes through ctx.llm.

The SDK generation span carries that turn to Langfuse.
Secrets are read before the app is built and again on each request.
"""

import os
from pathlib import Path

from keel import Agent


def load_secret_files() -> None:
    root = Path(os.environ.get("KEEL_SECRET_DIR", "/var/run/secrets/keel"))
    if not root.is_dir():
        return
    for name in ("KEEL_LLM_KEY", "KEEL_LLM_BASE_URL", "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY"):
        path = root / name
        if path.is_file():
            os.environ[name] = path.read_text(encoding="utf-8").strip()
    if os.environ.get("LANGFUSE_PUBLIC_KEY") and os.environ.get("LANGFUSE_SECRET_KEY"):
        os.environ.setdefault("LANGFUSE_HOST", "https://jp.cloud.langfuse.com")
        os.environ.setdefault("KEEL_TRACE_BUFFER_PATH", "/tmp/keel-traces")
    base = os.environ.get("KEEL_LLM_BASE_URL", "").rstrip("/")
    if base and not base.endswith("/v1"):
        os.environ["KEEL_LLM_BASE_URL"] = base + "/v1"


os.environ.setdefault("KEEL_LLM_BASE_URL", "http://keel-llm.keel-system.svc.cluster.local:8088")
os.environ.setdefault("KEEL_LLM_KEY", "pending")
load_secret_files()

agent = Agent.from_manifest("agent.yaml")


@agent.entry
async def chat(request, ctx):
    text = request.input.get("text") or ""
    reply = await ctx.llm.chat([{"role": "user", "content": text}])
    return ctx.final(reply or "")


class SecretFiles:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope.get("type") == "http":
            load_secret_files()
        await self.app(scope, receive, send)


app = SecretFiles(agent.asgi())
