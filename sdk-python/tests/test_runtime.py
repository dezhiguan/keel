import asyncio
import json
from pathlib import Path

import httpx
import jsonschema
import pytest
from fastapi import FastAPI

import keel
from keel import Agent
from keel.asgi import mount_to
from keel.protocol.errors import ErrorCode


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "contracts/sse-events.schema.json").read_text())


def agent_file(tmp_path, *, models=False):
    path = tmp_path / "agent.yaml"
    path.write_text("apiVersion: keel/v1\nmetadata:\n  name: hello-agent\nspec:\n"
                    "  runtime:\n    endpoint: http://localhost:8000\n" +
                    ("  models:\n    default: test-model\n" if models else ""))
    return path


def frames(response):
    result = []
    for block in response.text.strip().split("\n\n"):
        lines = block.splitlines()
        frame = {"event": lines[0].removeprefix("event: "),
                 "data": json.loads(lines[1].removeprefix("data: "))}
        jsonschema.Draft202012Validator(SCHEMA).validate(frame)
        result.append(frame)
    return result


async def call(app, method, path, **kwargs):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                 base_url="http://test") as client:
        return await client.request(method, path, **kwargs)


def test_invoke_protocol_health_manifest_and_feedback(tmp_path):
    agent = Agent.from_manifest(agent_file(tmp_path))

    @agent.entry
    async def handle(req, ctx):
        assert req.input["text"] == "hello"
        ctx.step("prepare")
        ctx.token("world")
        return ctx.final("world")

    app = agent.asgi()
    response = asyncio.run(call(app, "POST", "/v1/invoke", json={"input": {"text": "hello"}}))
    assert response.status_code == 200
    assert [frame["event"] for frame in frames(response)] == ["step", "token", "final"]
    assert frames(response)[-1]["data"]["run_id"]
    assert asyncio.run(call(app, "GET", "/v1/health")).json() == {
        "status": "ok", "agent": "hello-agent"}
    manifest = asyncio.run(call(app, "GET", "/v1/manifest")).json()["manifest"]
    jsonschema.validate(manifest, json.loads((ROOT / "contracts/manifest.schema.json").read_text()))
    assert asyncio.run(call(app, "POST", "/v1/feedback",
                            json={"trace_id": "trace-1", "value": 1})).status_code == 204


def test_runtime_error_is_sse_and_suspend_ends_stream(tmp_path):
    failing = Agent.from_manifest(agent_file(tmp_path))

    @failing.entry
    async def handle(req, ctx):
        raise RuntimeError("private user text")

    response = asyncio.run(call(failing.asgi(), "POST", "/v1/invoke",
                                json={"input": {"text": "hello"}}))
    frame = frames(response)[0]
    assert frame["event"] == "error"
    assert frame["data"]["code"] == ErrorCode.SERVER_INTERNAL_ERROR.value
    assert "private user text" not in response.text

    suspended = Agent.from_manifest(agent_file(tmp_path))

    @suspended.entry
    async def handle_suspend(req, ctx):
        ctx.step("approval")
        return ctx.suspend("approval", ref="approval-1")

    response = asyncio.run(call(suspended.asgi(), "POST", "/v1/invoke",
                                json={"input": {"text": "hello"}}))
    assert [frame["event"] for frame in frames(response)] == ["step", "suspend"]
    assert frames(response)[-1]["data"]["resume_token"]


def test_mount_preserves_routes_and_rejects_collision(tmp_path):
    agent = Agent.from_manifest(agent_file(tmp_path))

    @agent.entry
    async def handle(req, ctx):
        return ctx.final("ok")

    host = FastAPI()

    @host.get("/chat")
    def chat():
        return {"ok": True}

    keel.asgi.mount_to(host)
    assert asyncio.run(call(host, "GET", "/chat")).json() == {"ok": True}
    assert asyncio.run(call(host, "GET", "/v1/health")).status_code == 200

    conflict = FastAPI()

    @conflict.get("/v1/health")
    def old_health():
        return {"old": True}

    with pytest.raises(ValueError, match="/v1/health"):
        mount_to(conflict, agent)
    assert asyncio.run(call(conflict, "GET", "/v1/health")).json() == {"old": True}


def test_manifest_config_and_invalid_input(tmp_path, monkeypatch):
    agent = Agent.from_manifest(agent_file(tmp_path, models=True))
    monkeypatch.delenv("KEEL_LLM_BASE_URL", raising=False)
    monkeypatch.delenv("KEEL_LLM_KEY", raising=False)
    with pytest.raises(RuntimeError, match="KEEL_LLM_BASE_URL"):
        agent.asgi()
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "http://litellm.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-only")

    @agent.entry
    async def handle(req, ctx):
        return ctx.final("ok")

    app = agent.asgi()
    response = asyncio.run(call(app, "POST", "/v1/invoke", json={"input": {}}))
    assert response.status_code == 400
    assert response.json()["code"] == ErrorCode.SERVER_INVALID_PARAM.value


def test_disconnect_cancels_invocation(tmp_path):
    agent = Agent.from_manifest(agent_file(tmp_path))
    cancelled = asyncio.Event()

    @agent.entry
    async def handle(req, ctx):
        ctx.step("started")
        try:
            await asyncio.sleep(60)
        except asyncio.CancelledError:
            cancelled.set()
            raise

    app = agent.asgi()

    async def scenario():
        inbox = asyncio.Queue()
        inbox.put_nowait({"type": "http.request", "body": b'{"input":{"text":"hi"}}',
                         "more_body": False})

        async def receive():
            return await inbox.get()

        async def send(message):
            if message["type"] == "http.response.body" and b"event: step" in message.get("body", b""):
                inbox.put_nowait({"type": "http.disconnect"})

        scope = {"type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
                 "http_version": "1.1", "method": "POST", "scheme": "http",
                 "path": "/v1/invoke", "raw_path": b"/v1/invoke", "query_string": b"",
                 "root_path": "", "headers": [], "client": ("test", 123), "server": ("test", 80)}
        await asyncio.wait_for(app(scope, receive, send), timeout=1)
        assert cancelled.is_set()

    asyncio.run(scenario())
