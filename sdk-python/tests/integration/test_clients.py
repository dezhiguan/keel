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
from keel.llm.client import ChatResult, LlmClient, ToolCall
from keel.prompts import Prompt
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
        return httpx.Response(200, headers={"x-request-id": "gateway-request-1"}, json={
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
    assert json.loads(span.attributes["langfuse.observation.input"]) == [{"role": "user", "content": "hello"}]
    assert span.attributes["langfuse.observation.output"] == "hello"
    assert span.attributes["gen_ai.usage.input_tokens"] == 3
    assert span.attributes["keel.llm.request_id"] == "gateway-request-1"
    assert "zero cost" in caplog.text
    provider.shutdown()


def test_chat_does_not_send_the_keel_namespace(monkeypatch):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test/v1")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    requests = []

    def handle(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={
            "id": "chatcmpl-keel", "object": "chat.completion", "created": 1,
            "model": "qwen-plus", "choices": [{"index": 0,
            "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })

    provider = TracerProvider()
    client = LlmClient("hello-agent", "qwen-plus", "trace-keel", provider.get_tracer("test"),
                       httpx.AsyncClient(transport=httpx.MockTransport(handle)))
    prompt = Prompt("hello-agent/answer", 2, "text", False,
                    {"temperature": 0.2, "keel": {"sections": [{"kind": "role", "text": "值班"}]}},
                    "你好", {"sections": [{"kind": "role", "text": "值班"}]})

    async def scenario():
        result = await client.chat([{"role": "user", "content": "你好"}], prompt=prompt)
        await client.aclose()
        return result

    assert run(scenario()) == "ok"
    assert requests[0]["temperature"] == 0.2
    assert "keel" not in requests[0]
    provider.shutdown()


@pytest.mark.parametrize("arguments,expected", [('{"city":"上海"}', {"city": "上海"}),
                                                      ('[]', None), ('not-json', None)])
def test_chat_forwards_tools_and_returns_structured_calls(monkeypatch, arguments, expected):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    requests = []

    def handle(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={
            "id": "chatcmpl-tools", "object": "chat.completion", "created": 1,
            "model": "qwen-plus", "choices": [{"index": 0,
                "message": {"role": "assistant", "content": None, "tool_calls": [{
                    "id": "call-1", "type": "function", "function": {
                        "name": "weather.lookup", "arguments": arguments}}]},
                "finish_reason": "tool_calls"}],
            "usage": {"prompt_tokens": 4, "completion_tokens": 3, "total_tokens": 7},
        })

    client = LlmClient("agent", "qwen-plus", "trace-1", http_client=httpx.AsyncClient(
        transport=httpx.MockTransport(handle)))
    tools = [{"type": "function", "function": {"name": "weather.lookup",
              "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}}]

    async def scenario():
        try:
            if expected is None:
                with pytest.raises(KeelError) as error:
                    await client.chat([{"role": "user", "content": "天气"}], tools=tools)
                assert error.value.code is ErrorCode.SERVER_INTERNAL_ERROR
            else:
                result = await client.chat([{"role": "user", "content": "天气"}], tools=tools)
                assert result == ChatResult("", (ToolCall("call-1", "weather.lookup", expected),))
        finally:
            await client.aclose()

    run(scenario())
    assert requests[0]["tools"] == tools
    assert requests[0]["metadata"]["caller_agent"] == "agent"


def test_chat_with_tools_and_no_tool_call_returns_content(monkeypatch):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    client = LlmClient("agent", "qwen-plus", "trace-1", http_client=httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json={
            "id": "chatcmpl-final", "object": "chat.completion", "created": 1,
            "model": "qwen-plus", "choices": [{"index": 0,
                "message": {"role": "assistant", "content": "完成"}, "finish_reason": "stop"}],
        }))))

    async def scenario():
        try:
            return await client.chat([{"role": "user", "content": "你好"}], tools=[])
        finally:
            await client.aclose()

    assert run(scenario()) == ChatResult("完成", ())


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


def test_ragforge_default_client_uses_api_key_header(monkeypatch):
    monkeypatch.setenv("KEEL_RAGFORGE_URL", "https://ragforge.test")
    monkeypatch.setenv("KEEL_RAGFORGE_TOKEN", "scoped-read-key")
    requests = []

    async def send(client, request, **kwargs):
        requests.append(request)
        assert request.headers["X-API-Key"] == "scoped-read-key"
        assert "authorization" not in request.headers
        return httpx.Response(200, json={"code": 200, "data": {"results": []}}, request=request)

    monkeypatch.setattr(httpx.AsyncClient, "send", send)

    async def scenario():
        client = KnowledgeClient("rag-agent")
        try:
            return await client.search(17, "policy")
        finally:
            await client.aclose()

    assert run(scenario()).results == []
    assert json.loads(requests[0].content)["kbIds"] == [17]


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


def test_stream_emits_each_content_delta_before_the_full_reply(monkeypatch):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test/v1")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    seen = []

    def chunk(text, finish=None):
        body = {"id": "1", "object": "chat.completion.chunk", "created": 1, "model": "qwen-plus",
                "choices": [{"index": 0, "delta": {"content": text} if text else {}, "finish_reason": finish}]}
        return "data: " + json.dumps(body) + "\n\n"

    def handle(request):
        payload = json.loads(request.content)
        assert payload["stream"] is True
        raw = chunk("你") + chunk("好") + chunk("", "stop") + "data: [DONE]\n\n"
        return httpx.Response(200, headers={"content-type": "text/event-stream", "x-request-id": "gateway-stream-1"}, content=raw.encode())

    provider = TracerProvider()
    memory = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(memory))
    client = LlmClient("echo", "qwen-plus", "trace-1", provider.get_tracer("test"), on_token=seen.append,
                       http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))

    async def scenario():
        try:
            return await client.chat([{"role": "user", "content": "你好"}])
        finally:
            await client.aclose()

    assert run(scenario()) == "你好"
    assert seen == ["你", "好"]
    assert memory.get_finished_spans()[0].attributes["keel.llm.request_id"] == "gateway-stream-1"
    provider.shutdown()


def test_no_vendor_sdk_direct_imports():
    forbidden = ("dashscope", "anthropic", "google.generativeai")
    for source in (ROOT / "sdk-python/keel").rglob("*.py"):
        imports = (node for node in ast.walk(ast.parse(source.read_text()))
                   if isinstance(node, (ast.Import, ast.ImportFrom)))
        for node in imports:
            modules = ([alias.name for alias in node.names] if isinstance(node, ast.Import)
                       else [node.module or ""])
            assert all(not module.startswith(forbidden) for module in modules)


@pytest.mark.parametrize("stream", [False, True])
@pytest.mark.parametrize("upstream,expected", [("LLM_RATE_LIMITED", ErrorCode.LLM_RATE_LIMITED),
                                               ("LLM_BUDGET_EXCEEDED", ErrorCode.LLM_BUDGET_EXCEEDED),
                                               ("UNKNOWN", ErrorCode.GW_QUOTA_EXCEEDED),
                                               ("SERVER_INTERNAL_ERROR", ErrorCode.GW_QUOTA_EXCEEDED)])
def test_gateway_429_preserves_registered_quota_reason(monkeypatch, stream, upstream, expected):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test/v1")
    monkeypatch.setenv("KEEL_LLM_KEY", "virtual-test-key")
    calls = []
    def handle(request):
        calls.append(request)
        return httpx.Response(429, json={"code": upstream, "message": "quota reached"})
    client = LlmClient("agent", "qwen-plus", "trace", http_client=httpx.AsyncClient(
        transport=httpx.MockTransport(handle)), on_token=(lambda token: None) if stream else None)
    async def scenario():
        try:
            with pytest.raises(KeelError) as error:
                await client.chat([{"role": "user", "content": "hello"}])
            assert error.value.code is expected
        finally:
            await client.aclose()
    run(scenario())
    assert len(calls) == 1
