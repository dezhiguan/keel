import asyncio
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading

import pytest

from keel.context import Context
from keel.protocol.errors import ErrorCode, KeelError


def run(coro):
    return asyncio.run(coro)


def test_meta_agent_and_eval_traffic_cannot_remember(monkeypatch):
    monkeypatch.setenv("KEEL_SERVER_URL", "http://127.0.0.1:9")
    meta = Context("meta-agent", "run-1", "trace-1", asyncio.Queue(), env="dev")
    with pytest.raises(KeelError) as denied:
        run(meta.memory.remember("u_1", "秘密"))
    assert denied.value.code is ErrorCode.MEMORY_WRITE_DENIED
    eval_ctx = Context("careermate", "run-1", "trace-1", asyncio.Queue(), env="dev", eval_run_id="eval-1")
    with pytest.raises(KeelError) as blocked:
        run(eval_ctx.memory.remember("u_1", "秘密"))
    assert blocked.value.code is ErrorCode.MEMORY_WRITE_DENIED


def test_deleted_memory_is_not_recalled(monkeypatch):
    rows = [{"id": 7, "ownerUser": "u_1", "content": "偏好上海"}]

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self._json({"data": {"items": rows}})

        def do_DELETE(self):
            rows.clear()
            self._json({"data": {"id": 7, "items": []}})

        def _json(self, body):
            raw = json.dumps(body).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("KEEL_SERVER_URL", f"http://127.0.0.1:{server.server_address[1]}")
    try:
        ctx = Context("careermate", "run-1", "trace-1", asyncio.Queue(), env="dev")
        assert run(ctx.memory.recall("u_1"))[0]["content"] == "偏好上海"
        run(ctx.memory.forget(7))
        assert run(ctx.memory.recall("u_1")) == []
    finally:
        server.shutdown()
