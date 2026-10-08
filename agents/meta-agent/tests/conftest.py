"""Real HTTP fakes for the thin gateway and keel-lite; LlmClient itself is not mocked."""

import json
import os
import socket
import sys
import threading
from pathlib import Path

import pytest
import uvicorn
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
sys.path.insert(0, str(ROOT))


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _serve(app) -> int:
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error"))
    threading.Thread(target=server.run, daemon=True).start()
    for _ in range(200):
        if server.started:
            return port
        threading.Event().wait(0.05)
    raise RuntimeError("fake server did not start")


class FakeGateway:
    def __init__(self):
        self.replies: list[str] = []
        self.requests: list[dict] = []

    async def chat(self, request: Request):
        body = await request.json()
        self.requests.append(body)
        content = self.replies.pop(0) if self.replies else "{}"
        return JSONResponse({
            "id": "chatcmpl-fake", "object": "chat.completion", "created": 1, "model": body.get("model"),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
        })


GATEWAY = FakeGateway()
_gateway_port = _serve(Starlette(routes=[Route("/v1/chat/completions", GATEWAY.chat, methods=["POST"])]))

os.environ["KEEL_ENV"] = "dev"
os.environ["KEEL_LOCAL_PROMPTS"] = "1"
os.environ["KEEL_LLM_BASE_URL"] = f"http://127.0.0.1:{_gateway_port}/v1"
os.environ["KEEL_LLM_KEY"] = "test-virtual-key"
os.environ["KEEL_AUDIT_TOKEN"] = "local-dev-token"
os.environ["KEEL_AUDIT_SPOOL_PATH"] = ".keel/audit-spool.jsonl"
os.environ.pop("META_TOOL_CATALOG", None)

from keel.lite import LiteServer, LiteStore  # noqa: E402

os.environ["KEEL_AUDIT_URL"] = f"http://127.0.0.1:{_serve(LiteServer(LiteStore('.'), ('target_agent', 'version')))}"


@pytest.fixture
def gateway():
    GATEWAY.replies.clear()
    GATEWAY.requests.clear()
    return GATEWAY


@pytest.fixture
def catalog(tmp_path, monkeypatch):
    path = tmp_path / "catalog.json"

    def write(rows):
        path.write_text(json.dumps(rows), encoding="utf-8")
        monkeypatch.setenv("META_TOOL_CATALOG", str(path))
    return write
