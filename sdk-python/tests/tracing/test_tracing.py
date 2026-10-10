import asyncio
import ast
import json
import threading
from pathlib import Path

import httpx
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from keel import Agent
from keel.tracing import attrs
from keel.tracing.buffer import TraceBuffer
from keel.tracing.langgraph import LangGraphCallback
from keel.tracing.otel import OtlpHttpSpanExporter


ROOT = Path(__file__).resolve().parents[3]


def test_otlp_http_headers_buffer_and_replay(tmp_path):
    received = []
    online = False

    def handler(request):
        received.append(request)
        return httpx.Response(200 if online else 503)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    buffer = TraceBuffer(tmp_path / "traces.jsonl")
    exporter = OtlpHttpSpanExporter("https://langfuse.test", "pk-test", "sk-test", buffer, client)
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    with provider.get_tracer("test").start_as_current_span("agent.run", attributes={attrs.AGENT: "test"}):
        pass

    assert received[0].url.path == "/api/public/otel/v1/traces"
    assert received[0].headers["x-langfuse-ingestion-version"] == "4"
    assert received[0].headers["content-type"] == "application/x-protobuf"
    assert received[0].headers["authorization"].startswith("Basic ")
    assert len(buffer.path.read_text().splitlines()) == 1

    online = True
    assert buffer.replay(exporter._send) == 1
    assert buffer.replay(exporter._send) == 0
    assert buffer.path.read_text() == ""
    payload = ExportTraceServiceRequest.FromString(received[-1].content)
    spans = payload.resource_spans[0].scope_spans[0].spans
    assert len(spans) == 1
    assert spans[0].name == "agent.run"
    provider.shutdown()


def test_buffer_is_bounded_and_drops_oldest(tmp_path, caplog):
    buffer = TraceBuffer(tmp_path / "traces.jsonl", max_entries=2)
    buffer.append(b"first", trace_id="t1", agent="a")
    buffer.append(b"second", trace_id="t1", agent="a")
    buffer.append(b"third", trace_id="t1", agent="a")
    replayed = []
    assert buffer.replay(lambda data: replayed.append(data) or True) == 2
    assert replayed == [b"second", b"third"]
    assert "trace buffer dropped oldest batches" in caplog.text


def test_buffer_replays_in_background_after_recovery(tmp_path):
    buffer = TraceBuffer(tmp_path / "traces.jsonl")
    buffer.append(b"offline-span")
    available = threading.Event()
    delivered = threading.Event()

    def send(payload):
        if not available.is_set():
            return False
        assert payload == b"offline-span"
        delivered.set()
        return True

    buffer.start(send, interval_seconds=0.01)
    available.set()
    assert delivered.wait(timeout=1)
    buffer.close()
    assert buffer.pending() == 0


def test_root_and_langgraph_child_spans_exclude_user_text(tmp_path, monkeypatch):
    provider = TracerProvider()
    memory = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(memory))
    monkeypatch.setattr("keel.asgi.configure_from_env", lambda: provider)
    path = tmp_path / "agent.yaml"
    path.write_text("apiVersion: keel/v1\nmetadata:\n  name: test-agent\nspec:\n"
                    "  runtime:\n    endpoint: http://localhost:8000\n")
    agent = Agent.from_manifest(path)

    @agent.entry
    async def handle(req, ctx):
        ctx.step("retrieve")
        callback = LangGraphCallback(agent.name, provider.get_tracer("keel.langgraph"))
        callback.on_chain_start({"name": "router"}, {"input": req.input["text"]}, run_id="chain")
        callback.on_tool_start({"name": "search"}, req.input["text"],
                               run_id="tool", parent_run_id="chain")
        callback.on_tool_end(req.input["text"], run_id="tool")
        callback.on_chain_end({"output": req.input["text"]}, run_id="chain")
        return ctx.final("safe answer")

    async def invoke():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=agent.asgi()),
                                     base_url="http://test") as client:
            return await client.post("/v1/invoke", json={
                "input": {"text": "SECRET USER TEXT"},
                "context": {"user_id": "u_88"},
            })

    assert asyncio.run(invoke()).status_code == 200
    spans = memory.get_finished_spans()
    assert {span.name for span in spans} == {"agent.run", "retrieve", "router", "search"}
    names = {span.name: span for span in spans}
    assert names["router"].context.trace_id == names["agent.run"].context.trace_id
    assert names["search"].parent.span_id == names["router"].context.span_id
    root = names["agent.run"].attributes
    assert root["langfuse.observation.input"] == "SECRET USER TEXT"
    assert root["langfuse.user.id"] == "u_88"
    assert "SECRET USER TEXT" not in str([
        (span.name, span.attributes) for span in spans if span.name != "agent.run"])
    provider.shutdown()


def test_status_mapping_and_forbidden_imports():
    assert attrs.askdb_status("hit") == "ok"
    assert attrs.askdb_status("degraded") == "fallback"
    assert attrs.askdb_status("skipped") == "failed"
    for source in (ROOT / "sdk-python/keel").rglob("*.py"):
        if "eval" in source.parts or "gate" in source.parts:
            continue
        tree = ast.parse(source.read_text())
        imports = [node for node in ast.walk(tree) if isinstance(node, (ast.Import, ast.ImportFrom))]
        for node in imports:
            if isinstance(node, ast.Import):
                assert all(not alias.name.startswith("langfuse") for alias in node.names)
            else:
                assert not (node.module or "").startswith("langfuse")
                assert all(alias.name != "OtlpGrpcSpanExporter" for alias in node.names)
