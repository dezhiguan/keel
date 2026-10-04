"""Real echo agent: one user turn goes through ctx.llm and the SDK span exporter."""

import os
from pathlib import Path

from keel import Agent

agent = Agent.from_manifest("agent.yaml")


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


@agent.entry
async def chat(request, ctx):
    load_secret_files()
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
