import asyncio
import ast
import json
from pathlib import Path

import httpx
import pytest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from keel.audit.reporter import AuditReporter
from keel.context import Context
from keel.knowledge import KnowledgeClient
from keel.llm.client import LlmClient
from keel.manifest import AgentManifest
from keel.protocol.errors import ErrorCode, KeelError


ROOT = Path(__file__).resolve().parents[3]


def run(coro):
    return asyncio.run(coro)


def test_litellm_request_generation_span_and_zero_cost_warning(monkeypatch, caplog):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://litellm.test/v1")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={
            "id": "chatcmpl-test", "object": "chat.completion", "created": 1,
            "model": "qwen-plus", "choices": [{"index": 0,
            "message": {"role": "assistant", "content": "hello"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5},
            "response_cost": 0,
        })

    provider = TracerProvider()
    memory = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(memory))
    client = LlmClient("hello-agent", "qwen-plus", "trace-1", provider.get_tracer("test"),
                       httpx.AsyncClient(transport=httpx.MockTransport(handle)))

    async def scenario():
        result = await client.chat([{"role": "user", "content": "hello"}])
        await client.aclose()
        return result

    assert run(scenario()) == "hello"
    assert requests[0].url.path == "/v1/chat/completions"
    assert requests[0].headers["authorization"] == "Bearer test-virtual-key"
    body = json.loads(requests[0].content)
    assert body["metadata"]["caller_agent"] == "hello-agent"
    assert body["model"] == "qwen-plus"
    span = memory.get_finished_spans()[0]
    assert span.attributes["langfuse.observation.type"] == "generation"
    assert span.attributes["gen_ai.usage.input_tokens"] == 3
    assert "zero cost" in caplog.text
    provider.shutdown()


@pytest.mark.parametrize("status,expected", [(429, ErrorCode.GW_QUOTA_EXCEEDED),
                                              (500, ErrorCode.SERVER_INTERNAL_ERROR)])
def test_litellm_http_errors_use_registered_codes(monkeypatch, status, expected):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://litellm.test/v1")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    client = LlmClient("agent", "qwen-plus", "trace-1", http_client=httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(status, json={"error": "bad"}))))

    async def scenario():
        try:
            with pytest.raises(KeelError) as error:
                await client.chat([{"role": "user", "content": "hello"}])
            return error.value
        finally:
            await client.aclose()

    error = run(scenario())
    assert error.code is expected
    if status == 500:
        assert "HTTP 500" in str(error)


def test_gateway_root_without_v1_still_posts_chat_completions(monkeypatch):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://litellm.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={
            "id": "chatcmpl-test", "object": "chat.completion", "created": 1,
            "model": "qwen-plus", "choices": [{"index": 0,
            "message": {"role": "assistant", "content": "hello"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })

    client = LlmClient("echo", "qwen-plus", "trace-1", http_client=httpx.AsyncClient(
        transport=httpx.MockTransport(handle)))

    async def scenario():
        try:
            return await client.chat([{"role": "user", "content": "hello"}])
        finally:
            await client.aclose()

    assert run(scenario()) == "hello"
    assert requests[0].url.path == "/v1/chat/completions"


def test_ragforge_name_resolution_search_and_citations(monkeypatch):
    monkeypatch.setenv("KEEL_RAGFORGE_URL", "https://ragforge.test")
    monkeypatch.setenv("KEEL_RAGFORGE_TOKEN", "test-token")
    requests = []

    def handle(request):
        requests.append(request)
        if request.url.path == "/api/v1/knowledge-bases":
            return httpx.Response(200, json={"code": 200,
                                              "data": [{"id": 17, "name": "dev-standards"}]})
        return httpx.Response(200, json={"code": 200, "data": {"results": [
            {"docId": 11, "chunkId": 42, "filename": "guide.md",
             "content": "a rule", "finalScore": 0.9}]}})

    client = KnowledgeClient("hello-agent", http_client=httpx.AsyncClient(
        base_url="https://ragforge.test", headers={"Authorization": "Bearer test-token"},
        transport=httpx.MockTransport(handle)))

    async def scenario():
        result = await client.search("dev-standards", "policy", top_k=5)
        await client.aclose()
        return result

    result = run(scenario())
    assert len(requests) == 2
    assert requests[1].url.path == "/api/v1/search"
    assert requests[1].headers["x-keel-caller-agent"] == "hello-agent"
    assert json.loads(requests[1].content) == {
        "kbIds": [17], "query": "policy", "topK": 5, "caller_agent": "hello-agent"}
    assert result.citations == [{"doc_id": 11, "chunk_id": 42,
                                 "filename": "guide.md", "score": 0.9}]


@pytest.mark.parametrize("failure", ["timeout", 429, 500])
def test_ragforge_failures_use_registered_codes(monkeypatch, failure):
    monkeypatch.setenv("KEEL_RAGFORGE_URL", "https://ragforge.test")
    monkeypatch.setenv("KEEL_RAGFORGE_TOKEN", "test-token")

    def handle(request):
        if failure == "timeout":
            raise httpx.ReadTimeout("timeout")
        return httpx.Response(failure, json={"code": failure})

    client = KnowledgeClient("agent", http_client=httpx.AsyncClient(
        base_url="https://ragforge.test", transport=httpx.MockTransport(handle)))

    async def scenario():
        try:
            with pytest.raises(KeelError) as error:
                await client.search(17, "query")
            return error.value.code
        finally:
            await client.aclose()

    assert run(scenario()) is (ErrorCode.GW_QUOTA_EXCEEDED if failure == 429
                               else ErrorCode.SERVER_INTERNAL_ERROR)


def test_high_risk_audit_fails_closed_before_tool_execution(tmp_path, monkeypatch):
    monkeypatch.setenv("KEEL_AUDIT_URL", "https://audit.test")
    monkeypatch.setenv("KEEL_AUDIT_TOKEN", "test-token")
    sent = []
    executed = []

    def handle(request):
        sent.append(json.loads(request.content))
        return httpx.Response(500, json={"code": "SERVER_INTERNAL_ERROR"})

    reporter = AuditReporter("hello-agent", "dev", ("approved_field",),
                             spool_path=tmp_path / "audit.jsonl", http_client=httpx.AsyncClient(
                                 base_url="https://audit.test", transport=httpx.MockTransport(handle)))
    manifest = AgentManifest.model_validate({
        "apiVersion": "keel/v1", "metadata": {"name": "hello-agent"},
        "spec": {"runtime": {"endpoint": "http://localhost:8000"},
                 "tools": [{"name": "write", "risk": "high", "approval": "required"}],
                 "audit": {"captureFields": ["approved_field"]}}})

    async def tool(**kwargs):
        executed.append(kwargs)

    context = Context("hello-agent", "run-1", "trace-1", asyncio.Queue(),
                      manifest=manifest, tool_functions={"write": tool}, env="dev",
                      audit_reporter=reporter)

    async def scenario():
        try:
            with pytest.raises(KeelError) as error:
                await context.tools.call("write", approved_field="safe", secret="private")
            return error.value.code
        finally:
            await context.aclose()

    assert run(scenario()) is ErrorCode.AUDIT_WRITE_FAILED
    assert executed == []
    assert sent[0]["payload"] == {"approved_field": "safe"}
    assert "private" not in json.dumps(sent[0])


def test_async_audit_spools_on_full_queue_and_replays_in_order(tmp_path, monkeypatch):
    monkeypatch.setenv("KEEL_AUDIT_URL", "https://audit.test")
    monkeypatch.setenv("KEEL_AUDIT_TOKEN", "test-token")
    online = False
    delivered = []

    async def publisher(event, group):
        if not online:
            raise RuntimeError("MQ unavailable")
        delivered.append((event, group))

    reporter = AuditReporter("hello-agent", "dev", ("safe",), publisher=publisher,
                             spool_path=tmp_path / "audit.jsonl", queue_size=1)

    async def scenario():
        nonlocal online
        for index in range(3):
            await reporter.record("tool.call", "low", "allowed",
                                  payload={"safe": index, "secret": "never send"})
        assert reporter.queue.full()
        assert len(reporter.spool_path.read_text().splitlines()) == 3
        assert await reporter.replay_once() == 0
        online = True
        count = await reporter.replay_once()
        await reporter.aclose()
        return count

    assert run(scenario()) == 3
    assert [event["payload"] for event, _ in delivered] == [
        {"safe": 0}, {"safe": 1}, {"safe": 2}]
    assert all(group == "hello-agent" for _, group in delivered)
    assert reporter.spool_path.read_text() == ""


def test_no_vendor_sdk_direct_imports():
    forbidden = ("dashscope", "anthropic", "google.generativeai")
    for source in (ROOT / "sdk-python/keel").rglob("*.py"):
        imports = (node for node in ast.walk(ast.parse(source.read_text()))
                   if isinstance(node, (ast.Import, ast.ImportFrom)))
        for node in imports:
            modules = ([alias.name for alias in node.names] if isinstance(node, ast.Import)
                       else [node.module or ""])
            assert all(not module.startswith(forbidden) for module in modules)
