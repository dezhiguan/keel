import asyncio
import json

import httpx
import pytest
from opentelemetry.sdk.trace import TracerProvider

from keel.llm.client import LlmClient, estimate_context_tokens
from keel.protocol.errors import ErrorCode, KeelError


def run(coro):
    return asyncio.run(coro)


def reply():
    return httpx.Response(200, json={
        "id": "chatcmpl-budget", "object": "chat.completion", "created": 1,
        "model": "qwen-plus", "choices": [{"index": 0,
            "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    })


def client(monkeypatch, handle):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test/v1")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    return LlmClient("keel-assistant", "qwen-plus", "trace-budget",
                     TracerProvider().get_tracer("test"),
                     httpx.AsyncClient(transport=httpx.MockTransport(handle)))


def test_cjk_counts_as_one_token_and_ascii_as_four_characters():
    base = [{"role": "user", "content": "x"}]
    assert estimate_context_tokens([{"role": "user", "content": "x中中中中"}]) - estimate_context_tokens(base) == 4
    assert estimate_context_tokens([{"role": "user", "content": "xabcd"}]) - estimate_context_tokens(base) == 1


def test_oversized_context_is_rejected_before_the_gateway(monkeypatch):
    monkeypatch.setenv("KEEL_CONTEXT_TOKEN_BUDGET", "10000")
    calls = []
    llm = client(monkeypatch, lambda request: calls.append(request) or reply())
    messages = [{"role": "user", "content": "测" * 10001}]

    async def scenario():
        with pytest.raises(KeelError) as raised:
            await llm.chat(messages)
        await llm.aclose()
        return raised.value

    error = run(scenario())
    assert error.code == ErrorCode.AGENT_CONTEXT_EXCEEDED
    assert str(error) == "上下文窗口过大"
    assert error.code.retryable is False
    assert calls == []


def test_context_within_budget_still_calls_the_gateway(monkeypatch):
    monkeypatch.delenv("KEEL_CONTEXT_TOKEN_BUDGET", raising=False)
    calls = []
    llm = client(monkeypatch, lambda request: calls.append(json.loads(request.content)) or reply())

    async def scenario():
        result = await llm.chat([{"role": "user", "content": "你好"}])
        await llm.aclose()
        return result

    assert run(scenario()) == "ok"
    assert calls[0]["messages"][0]["content"] == "你好"


def test_exact_budget_is_sent_and_one_token_over_is_rejected(monkeypatch):
    messages = [{"role": "user", "content": "测" * 20}]
    used = estimate_context_tokens(messages)
    calls = []
    llm = client(monkeypatch, lambda request: calls.append(request) or reply())

    async def scenario():
        monkeypatch.setenv("KEEL_CONTEXT_TOKEN_BUDGET", str(used))
        sent = await llm.chat(messages)
        monkeypatch.setenv("KEEL_CONTEXT_TOKEN_BUDGET", str(used - 1))
        with pytest.raises(KeelError) as raised:
            await llm.chat(messages)
        await llm.aclose()
        return sent, raised.value

    sent, error = run(scenario())
    assert sent == "ok"
    assert len(calls) == 1
    assert error.code == ErrorCode.AGENT_CONTEXT_EXCEEDED


def test_tool_definitions_count_toward_the_budget(monkeypatch):
    monkeypatch.setenv("KEEL_CONTEXT_TOKEN_BUDGET", "10000")
    calls = []
    llm = client(monkeypatch, lambda request: calls.append(request) or reply())
    tools = [{"type": "function", "function": {"name": "lookup", "description": "料" * 10001, "parameters": {}}}]

    async def scenario():
        with pytest.raises(KeelError) as raised:
            await llm.chat([{"role": "user", "content": "查一下"}], tools=tools)
        await llm.aclose()
        return raised.value

    assert run(scenario()).code == ErrorCode.AGENT_CONTEXT_EXCEEDED
    assert calls == []


def test_zero_budget_disables_the_check(monkeypatch):
    monkeypatch.setenv("KEEL_CONTEXT_TOKEN_BUDGET", "0")
    calls = []
    llm = client(monkeypatch, lambda request: calls.append(request) or reply())

    async def scenario():
        result = await llm.chat([{"role": "user", "content": "测" * 10001}])
        await llm.aclose()
        return result

    assert run(scenario()) == "ok"
    assert len(calls) == 1


def test_summary_call_does_not_stream_tokens_to_the_chat(monkeypatch):
    requests = []
    llm = client(monkeypatch, lambda request: requests.append(json.loads(request.content)) or reply())
    emitted = []
    llm.on_token = emitted.append

    async def scenario():
        result = await llm.chat([{'role': 'user', 'content': '历史摘要'}], tools=[], emit_tokens=False, max_tokens=512)
        await llm.aclose()
        return result

    assert run(scenario()).content == 'ok'
    assert emitted == []
    assert requests[0]['max_tokens'] == 512
    assert requests[0].get('stream') is not True
