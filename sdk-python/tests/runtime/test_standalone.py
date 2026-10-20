import asyncio
import importlib.util
import json
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

from keel.manifest import load_manifest
from keel.prompts import PromptClient
from keel.runtime import mode


def test_production_profile_rejects_standalone(monkeypatch):
    monkeypatch.setenv("KEEL_MODE", "standalone")
    monkeypatch.setenv("KEEL_ENV", "prod")
    with pytest.raises(RuntimeError, match="production profile"):
        mode()
    monkeypatch.setenv("KEEL_ENV", "dev")
    monkeypatch.setenv("SPRING_PROFILES_ACTIVE", "local,prod")
    with pytest.raises(RuntimeError, match="production profile"):
        mode()


def test_governed_mode_still_requires_the_virtual_key(tmp_path, monkeypatch):
    from keel import Agent

    monkeypatch.delenv("KEEL_MODE", raising=False)
    monkeypatch.delenv("KEEL_LLM_KEY", raising=False)
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "http://127.0.0.1:9/v1")
    path = tmp_path / "agent.yaml"
    path.write_text("apiVersion: keel/v1\nkind: Agent\nmetadata:\n  name: hello-agent\n"
                    "spec:\n  runtime:\n    endpoint: http://127.0.0.1:8000\n"
                    "  models:\n    default: qwen-plus\n", encoding="utf-8")
    agent = Agent.from_manifest(path)

    @agent.entry
    async def handle(request, ctx):
        return ctx.final("ok")

    with pytest.raises(RuntimeError, match="KEEL_LLM_KEY"):
        agent.asgi()


def test_standalone_reads_local_prompts_without_langfuse(tmp_path, monkeypatch):
    monkeypatch.setenv("KEEL_MODE", "standalone")
    monkeypatch.setenv("KEEL_ENV", "dev")
    prompts = tmp_path / "prompts"
    prompts.mkdir()
    (prompts / "assistant.md").write_text("hello {{name}}\n", encoding="utf-8")
    manifest_path = tmp_path / "agent.yaml"
    manifest_path.write_text("""apiVersion: keel/v1
kind: Agent
metadata:
  name: demo-agent
spec:
  runtime:
    endpoint: http://127.0.0.1:8000
  prompts:
    source: langfuse
    items:
      - name: assistant
        type: text
""", encoding="utf-8")
    prompt = asyncio.run(PromptClient(load_manifest(manifest_path), "dev", tmp_path).get(
        "demo-agent", "assistant", "trace-1"))
    assert prompt.fallback is True
    assert prompt.compile(name="ada") == "hello ada\n"


def test_standalone_loop_agent_audits_locally_and_skips_the_gateway(tmp_path, monkeypatch):
    from keel.cli.new import create_project

    project = create_project("loop-demo", template="loop-agent", root=tmp_path)
    bodies = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length))
            bodies.append((self.path, self.headers.get("Authorization"), body))
            message = ({"role": "assistant", "content": "",
                        "tool_calls": [{"id": "call-1", "type": "function",
                                        "function": {"name": "echo", "arguments": '{"text":"hello"}'}}]}
                       if len(bodies) == 1 else
                       {"role": "assistant", "content": "hello"})
            if message.get("tool_calls"):
                message["tool_calls"][0]["index"] = 0
            chunk = {
                "id": "chatcmpl-standalone", "object": "chat.completion.chunk", "created": 1,
                "model": "qwen-plus", "choices": [{"index": 0, "delta": message,
                                                   "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 2, "completion_tokens": 2, "total_tokens": 4},
            }
            assert body["stream"] is True
            payload = ("data: " + json.dumps(chunk) + "\n\ndata: [DONE]\n\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.chdir(project)
    monkeypatch.setenv("KEEL_MODE", "standalone")
    monkeypatch.setenv("KEEL_ENV", "dev")
    monkeypatch.setenv("KEEL_LLM_BASE_URL", f"http://127.0.0.1:{server.server_address[1]}/v1")
    for name in ("KEEL_LLM_KEY", "KEEL_AUDIT_URL", "KEEL_AUDIT_TOKEN", "KEEL_SERVER_URL",
                 "LANGFUSE_HOST", "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY"):
        monkeypatch.delenv(name, raising=False)
    try:
        spec = importlib.util.spec_from_file_location("standalone_loop_app", project / "app.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        response = asyncio.run(_call(module.app, "POST", "/v1/invoke", json={"input": {"text": "repeat hello"}}))
    finally:
        server.shutdown()
        server.server_close()
    assert response.status_code == 200
    assert "hello" in response.text
    assert bodies[0][0] == "/v1/chat/completions"
    assert bodies[0][1] == "Bearer standalone"
    assert "metadata" not in bodies[0][2]
    assert all(path == "/v1/chat/completions" for path, _auth, _body in bodies)
    rows = sqlite3.connect(project / ".keel" / "dev.db").execute(
        "SELECT action, env FROM audit_event").fetchall()
    assert ("tool.call", "dev") in rows


async def _call(app, method, path, **kwargs):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.request(method, path, **kwargs)
