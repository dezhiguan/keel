import asyncio
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading

import pytest

from keel.context import Context
from keel.protocol.errors import ErrorCode, KeelError
from keel.skills import SkillClient


class Item:
    def __init__(self, name, version):
        self.name = name
        self.version = version


class Tool:
    def __init__(self, name):
        self.name = name


class Spec:
    def __init__(self, skills=None, tools=None):
        self.models = None
        self.knowledge = None
        self.audit = None
        self.skills = skills or []
        self.tools = tools or []


class Manifest:
    def __init__(self, spec):
        self.spec = spec


def run(coro):
    return asyncio.run(coro)


def test_an_undeclared_skill_is_rejected_and_does_not_grant_tools(monkeypatch):
    seen = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            seen.append(self.path)
            self.send_response(200)
            self.end_headers()

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("KEEL_SERVER_URL", f"http://127.0.0.1:{server.server_address[1]}")
    try:
        manifest = Manifest(Spec())
        context = Context("skill-agent", "run-1", "trace-1", asyncio.Queue(), manifest=manifest, env="dev")
        assert seen == []
        with pytest.raises(KeelError) as missing:
            run(context.skills.load("cite-from-kb"))
        assert missing.value.code is ErrorCode.SKILL_NOT_GRANTED
        assert seen == []
        with pytest.raises(KeelError) as tool:
            run(context.tools.call("rag.search"))
        assert tool.value.code is ErrorCode.TOOL_NOT_GRANTED
    finally:
        server.shutdown()


def test_load_reads_the_pinned_version_and_leaves_tools_alone(monkeypatch):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            body = json.dumps({"data": {
                "name": "cite-from-kb", "version": 1, "instructions": "找不到依据时说无法确认",
                "tools": ["rag.search"], "examples": ["标出处"],
            }}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("KEEL_SERVER_URL", f"http://127.0.0.1:{server.server_address[1]}")
    try:
        manifest = Manifest(Spec(skills=[Item("cite-from-kb", 1)]))
        client = SkillClient(manifest, "skill-agent", "dev")
        skill = run(client.load("cite-from-kb"))
        assert skill.instructions == "找不到依据时说无法确认"
        assert skill.tools == ["rag.search"]
        context = Context("skill-agent", "run-1", "trace-1", asyncio.Queue(), manifest=manifest, env="dev")
        with pytest.raises(KeelError) as tool:
            run(context.tools.call("rag.search"))
        assert tool.value.code is ErrorCode.TOOL_NOT_GRANTED
        mismatched = SkillClient(Manifest(Spec(skills=[Item("cite-from-kb", 2)])), "skill-agent", "dev")
        with pytest.raises(KeelError) as stale:
            run(mismatched.load("cite-from-kb"))
        assert stale.value.code is ErrorCode.SKILL_NOT_GRANTED
    finally:
        server.shutdown()
