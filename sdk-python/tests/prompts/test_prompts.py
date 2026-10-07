import json
from pathlib import Path

import httpx
import pytest

from keel.prompts import Prompt, PromptClient
from keel.protocol.errors import ErrorCode, KeelError


class Item:
    def __init__(self, name, type):
        self.name = name
        self.type = type


class Prompts:
    def __init__(self, items, label="production", source="langfuse"):
        self.items = items
        self.label = label
        self.source = source


class Spec:
    def __init__(self, prompts):
        self.prompts = prompts


class Manifest:
    def __init__(self, prompts):
        self.spec = Spec(prompts)


def run(coro):
    import asyncio
    return asyncio.run(coro)


def test_dev_encodes_the_name_and_reads_the_label(tmp_path):
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.raw_path.decode()
        return httpx.Response(200, json={
            "name": "prompt-lab/answer", "version": 3, "type": "text",
            "prompt": "只回答 BANANA。问题：{{question}}", "config": {"temperature": 0.2, "model": "ignored"},
        })

    transport = httpx.MockTransport(handler)

    async def fetch():
        client = PromptClient(Manifest(Prompts([Item("answer", "text")])), "dev", tmp_path)
        original = __import__("keel.prompts", fromlist=["httpx"])
        # The client builds its own AsyncClient. Patch via env and a local server instead.
        return client

    # Use a real local server so the client is not mocked.
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import threading

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            seen["path"] = self.path
            body = json.dumps({
                "name": "prompt-lab/answer", "version": 3, "type": "text",
                "prompt": "只回答 BANANA。问题：{{question}}",
                "config": {"temperature": 0.2, "model": "ignored"},
            }).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        import os
        os.environ["LANGFUSE_HOST"] = f"http://127.0.0.1:{server.server_address[1]}"
        os.environ["LANGFUSE_PUBLIC_KEY"] = "pk"
        os.environ["LANGFUSE_SECRET_KEY"] = "sk"
        prompt = run(PromptClient(Manifest(Prompts([Item("answer", "text")])), "dev", tmp_path)
                     .get("prompt-lab", "answer", "trace-1"))
        assert seen["path"].startswith("/api/public/v2/prompts/prompt-lab%2Fanswer?label=dev")
        assert prompt.version == 3
        assert prompt.fallback is False
        assert prompt.config == {"temperature": 0.2}
        assert prompt.compile(question="1+1") == "只回答 BANANA。问题：1+1"
        again = run(PromptClient(Manifest(Prompts([Item("answer", "text")])), "dev", tmp_path)
                    .get("prompt-lab", "answer", "trace-1"))
        # A new client does not share the cache; the same client does.
        client = PromptClient(Manifest(Prompts([Item("answer", "text")])), "dev", tmp_path)
        first = run(client.get("prompt-lab", "answer", "trace-1"))
        seen["path"] = ""
        second = run(client.get("prompt-lab", "answer", "trace-1"))
        assert second.version == first.version
        assert seen["path"] == ""
    finally:
        server.shutdown()


def test_dev_404_uses_the_local_copy_and_staging_does_not(tmp_path, monkeypatch):
    (tmp_path / "prompts").mkdir()
    (tmp_path / "prompts" / "answer.md").write_text("本地 {{question}}", encoding="utf-8")

    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import threading

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("LANGFUSE_HOST", f"http://127.0.0.1:{server.server_address[1]}")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "pk")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "sk")
    try:
        prompt = run(PromptClient(Manifest(Prompts([Item("answer", "text")])), "dev", tmp_path)
                     .get("prompt-lab", "answer", "trace-1"))
        assert prompt.fallback is True
        assert prompt.version is None
        assert prompt.compile(question="hi") == "本地 hi"
        with pytest.raises(KeelError) as exc:
            run(PromptClient(Manifest(Prompts([Item("answer", "text")])), "staging", tmp_path)
                .get("prompt-lab", "answer", "trace-1"))
        assert exc.value.code is ErrorCode.PROMPT_UNAVAILABLE
    finally:
        server.shutdown()


def test_missing_variable_and_undeclared_name(tmp_path):
    prompt = Prompt("a/answer", 1, "text", False, {}, "你好 {{name}}")
    with pytest.raises(KeelError) as exc:
        prompt.compile()
    assert exc.value.code is ErrorCode.PROMPT_VARIABLE_MISSING
    with pytest.raises(KeelError) as missing:
        run(PromptClient(Manifest(Prompts([Item("answer", "text")])), "dev", tmp_path)
            .get("prompt-lab", "other", "trace-1"))
    assert missing.value.code is ErrorCode.PROMPT_NOT_DECLARED
