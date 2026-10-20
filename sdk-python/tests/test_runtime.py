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
from keel.llm.client import ChatResult, ToolCall


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
    headers = kwargs.pop("headers", None)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                 base_url="http://test") as client:
        return await client.request(method, path, headers=headers, **kwargs)


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
    published = asyncio.run(call(app, "GET", "/v1/manifest")).json()
    from importlib.metadata import version
    assert published["sdkVersion"] == version("keel-sdk")
    manifest = published["manifest"]
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


def test_loop_step_limit_is_sent_as_sse_error(tmp_path, monkeypatch):
    path = agent_file(tmp_path, models=True)
    path.write_text(path.read_text() + "  tools:\n    - name: lookup\n")
    agent = Agent.from_manifest(path)
    for key, value in {"KEEL_LLM_BASE_URL": "https://gateway.test", "KEEL_LLM_KEY": "test-key",
                       "KEEL_AUDIT_URL": "https://audit.test", "KEEL_AUDIT_TOKEN": "test-token",
                       "KEEL_AUDIT_SPOOL_PATH": str(tmp_path / "audit.jsonl"), "KEEL_ENV": "dev"}.items():
        monkeypatch.setenv(key, value)

    class FakeLlm:
        def __init__(self, *args, **kwargs):
            pass

        async def chat(self, messages, tools, prompt=None):
            return ChatResult("", (ToolCall("call-1", "lookup", {}),))

        async def aclose(self):
            pass

    monkeypatch.setattr("keel.context.LlmClient", FakeLlm)

    class FakeAudit:
        async def record(self, *args, **kwargs):
            pass

        async def aclose(self):
            pass

    monkeypatch.setattr("keel.context.AuditReporter", lambda *args, **kwargs: FakeAudit())

    @agent.tool(name="lookup")
    async def lookup():
        return "ok"

    @agent.entry
    async def handle(req, ctx):
        return await ctx.loop([{"role": "user", "content": req.input["text"]}],
                              [{"type": "function", "function": {"name": "lookup", "parameters": {"type": "object"}}}],
                              max_steps=1)

    response = asyncio.run(call(agent.asgi(), "POST", "/v1/invoke", json={"input": {"text": "go"}}))
    assert [frame["event"] for frame in frames(response)] == ["step", "tool", "error"]
    assert frames(response)[-1]["data"]["code"] == ErrorCode.AGENT_MAX_STEPS_EXCEEDED.value


def test_loop_approval_resume_keeps_run_and_prior_tool_results(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("KEEL_RUN_STORE", str(tmp_path / "runs.json"))
    path = agent_file(tmp_path, models=True)
    path.write_text(path.read_text() + "  tools:\n    - name: lookup\n      risk: high\n      approval: required\n")
    agent = Agent.from_manifest(path)
    for key, value in {"KEEL_LLM_BASE_URL": "https://gateway.test", "KEEL_LLM_KEY": "test-key",
                       "KEEL_AUDIT_URL": "https://audit.test", "KEEL_AUDIT_TOKEN": "test-token",
                       "KEEL_AUDIT_SPOOL_PATH": str(tmp_path / "audit.jsonl"), "KEEL_ENV": "dev"}.items():
        monkeypatch.setenv(key, value)
    responses = [ChatResult("", (ToolCall("call-1", "lookup", {"n": 1}),)), ChatResult("done", ())]
    seen = []

    class FakeLlm:
        def __init__(self, *args, **kwargs):
            pass

        async def chat(self, messages, tools, prompt=None):
            if len(responses) == 1:
                assert messages[-1]["role"] == "tool"
            return responses.pop(0)

        async def aclose(self):
            pass

    class FakeAudit:
        async def record(self, *args, **kwargs):
            pass

        async def aclose(self):
            pass

    async def fake_suspend(ctx, tool, kwargs):
        ctx.pending = {"tool": tool, "kwargs": kwargs, "checkpoint_ref": f"ckpt_{ctx.run_id}",
                       "approval_id": "approval-1"}
        return ctx.suspend("approval", ref="approval-1")

    async def approved(record):
        return True

    monkeypatch.setattr("keel.context.LlmClient", FakeLlm)
    monkeypatch.setattr("keel.context.AuditReporter", lambda *args, **kwargs: FakeAudit())
    monkeypatch.setattr("keel.context.Context.suspend_for_approval", fake_suspend)
    monkeypatch.setattr("keel.asgi._approval_granted", approved)

    @agent.tool(name="lookup")
    async def lookup(n):
        seen.append(n)
        return {"value": n}

    @agent.entry
    async def handle(req, ctx):
        return await ctx.loop([{"role": "user", "content": req.input["text"]}],
                              [{"type": "function", "function": {"name": "lookup", "parameters": {"type": "object"}}}])

    app = agent.asgi()
    first = frames(asyncio.run(call(app, "POST", "/v1/invoke", json={"input": {"text": "go"}})))[-1]
    assert first["event"] == "suspend"
    assert seen == []
    run_id = first["data"]["run_id"]
    second = frames(asyncio.run(call(app, "POST", f"/v1/runs/{run_id}/resume", json={
        "resume_token": first["data"]["resume_token"], "decision": "approve"})))[-1]
    assert second["event"] == "final"
    assert second["data"]["run_id"] == run_id
    assert second["data"]["answer"] == "done"
    assert seen == [1]


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


def test_disconnect_cancels_invocation(tmp_path, monkeypatch):
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
    import keel.asgi as runtime
    from keel.tracing import attrs
    exported = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exported))
    monkeypatch.setattr(runtime, "configure_from_env", lambda: provider)
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
    root = next(span for span in exported.get_finished_spans() if span.name == "agent.run")
    assert root.attributes[attrs.STATUS] == "failed"
    assert root.attributes[attrs.OBSERVATION_OUTPUT] == "调用已取消"


def test_input_required_resume_reads_reply_and_can_suspend_again(tmp_path):
    agent = Agent.from_manifest(agent_file(tmp_path))
    seen = []

    @agent.entry
    async def handle(req, ctx):
        seen.append((getattr(req, "resume", False), req.input["text"]))
        if len(seen) < 3:
            return ctx.suspend("input_required", ref=ctx.run_id, prompt="继续?")
        return ctx.final("done")

    app = agent.asgi()
    first = frames(asyncio.run(call(app, "POST", "/v1/invoke", json={"input": {"text": "需求"}})))[-1]["data"]
    path = f"/v1/runs/{first['run_id']}/resume"
    missing = asyncio.run(call(app, "POST", path, json={"resume_token": first["resume_token"]}))
    assert missing.status_code == 400
    assert missing.json()["code"] == ErrorCode.SERVER_INVALID_PARAM.value
    second = frames(asyncio.run(call(app, "POST", path, json={
        "resume_token": first["resume_token"], "input": {"text": "改一下"}})))[-1]
    assert second["event"] == "suspend"
    third = frames(asyncio.run(call(app, "POST", path, json={
        "resume_token": second["data"]["resume_token"], "input": {"text": "确认"}})))[-1]
    assert third["event"] == "final"
    assert seen == [(False, "需求"), (True, "改一下"), (True, "确认")]
