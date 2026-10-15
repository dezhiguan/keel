import asyncio
import json

import httpx
import pytest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from keel.context import Context
from keel.llm.client import LlmClient
from keel.manifest import AgentManifest
from keel.protocol.errors import ErrorCode, KeelError


TOOLS = [{"type": "function", "function": {"name": "lookup", "parameters": {"type": "object"}}}]


def completion(content=None, calls=()):
    return {"id": "chatcmpl-loop", "object": "chat.completion", "created": 1, "model": "test-model",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content,
                                        "tool_calls": [{"id": f"call-{index}", "type": "function",
                                                        "function": {"name": name, "arguments": json.dumps(args)}}
                                                       for index, (name, args) in enumerate(calls)]},
                         "finish_reason": "tool_calls" if calls else "stop"}],
            "usage": {"prompt_tokens": 2, "completion_tokens": 2, "total_tokens": 4}}


class Audit:
    def __init__(self):
        self.rows = []

    async def record(self, action, risk, decision, **kwargs):
        self.rows.append((action, risk, decision))

    async def aclose(self):
        pass


def context(monkeypatch, replies, functions=None, *, high=False, budget=None, run_id="run-1", tracer=None):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    requests = []
    responses = list(replies)

    def handle(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=responses.pop(0))

    llm = LlmClient("loop-agent", "test-model", "trace-1", tracer,
                    httpx.AsyncClient(transport=httpx.MockTransport(handle)))
    manifest = AgentManifest.model_validate({
        "apiVersion": "keel/v1", "metadata": {"name": "loop-agent"},
        "spec": {"kind": "agent", "runtime": {"endpoint": "https://agent.test"},
                 "models": {"default": "test-model", "budget": {"maxSteps": 6}},
                 "tools": [{"name": "lookup", "risk": "high" if high else "low",
                            "approval": "required" if high else "none"}]},
    })
    ctx = Context("loop-agent", run_id, "trace-1", asyncio.Queue(), tracer,
                  manifest=manifest, tool_functions=functions or {}, budget=budget,
                  env="dev", llm_client=llm, audit_reporter=Audit())
    return ctx, requests


def test_loop_executes_tool_and_returns_model_completion(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    seen = []

    async def lookup(city):
        seen.append(city)
        return {"weather": "sunny"}

    provider = TracerProvider()
    memory = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(memory))
    ctx, requests = context(monkeypatch, [completion(calls=[("lookup", {"city": "上海"})]),
                                    completion("晴天")], {"lookup": lookup}, tracer=provider.get_tracer("test"))

    async def scenario():
        try:
            return await ctx.loop([{"role": "user", "content": "天气"}], TOOLS)
        finally:
            await ctx.aclose()

    result = asyncio.run(scenario())
    assert result.answer == "晴天"
    assert seen == ["上海"]
    assert requests[0]["tools"] == TOOLS
    assert requests[1]["messages"][-1] == {"role": "tool", "tool_call_id": "call-0",
                                           "content": '{"weather": "sunny"}'}
    assert [span.attributes["keel.loop.step"] for span in memory.get_finished_spans()
            if span.name == "agent.loop.step"] == [1, 2]
    provider.shutdown()


def test_loop_stops_on_max_steps_and_budget(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    calls = []
    ctx, requests = context(monkeypatch, [completion(calls=[("lookup", {"n": 1})])],
                            {"lookup": lambda n: calls.append(n) or "ok"})

    async def max_steps():
        try:
            with pytest.raises(KeelError) as error:
                await ctx.loop([{"role": "user", "content": "go"}], TOOLS, max_steps=1)
            return error.value.code
        finally:
            await ctx.aclose()

    assert asyncio.run(max_steps()) is ErrorCode.AGENT_MAX_STEPS_EXCEEDED
    assert calls == [1]
    assert len(requests) == 1

    empty, requests = context(monkeypatch, [], budget={"cny": 0, "steps": 6})

    async def budget():
        try:
            with pytest.raises(KeelError) as error:
                await empty.loop([{"role": "user", "content": "go"}], TOOLS)
            return error.value.code
        finally:
            await empty.aclose()

    assert asyncio.run(budget()) is ErrorCode.GW_QUOTA_EXCEEDED
    assert requests == []


def test_loop_rejects_repeated_identical_tool_call(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    seen = []
    reply = completion(calls=[("lookup", {"n": 1})])
    ctx, requests = context(monkeypatch, [reply, reply], {"lookup": lambda n: seen.append(n) or "ok"})

    async def scenario():
        try:
            with pytest.raises(KeelError, match="连续重复"):
                await ctx.loop([{"role": "user", "content": "go"}], TOOLS)
        finally:
            await ctx.aclose()

    asyncio.run(scenario())
    assert seen == [1]
    assert len(requests) == 2


def test_high_risk_tool_resumes_from_checkpoint_and_next_one_needs_approval(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    seen = []

    async def lookup(n):
        seen.append(n)
        return {"value": n}

    first, requests = context(monkeypatch, [completion(calls=[("lookup", {"n": 1})])],
                              {"lookup": lookup}, high=True)
    suspensions = []

    async def suspend(tool, kwargs):
        suspensions.append(kwargs["n"])
        first.pending = {"tool": tool, "kwargs": kwargs, "checkpoint_ref": "ckpt_run-1"}
        return first.suspend("approval", ref="approval-1")

    first.suspend_for_approval = suspend

    async def initial():
        try:
            return await first.loop([{"role": "user", "content": "go"}], TOOLS)
        finally:
            await first.aclose()

    event = asyncio.run(initial())
    assert event.reason.value == "approval"
    assert suspensions == [1]
    assert seen == []
    checkpoint = tmp_path / ".keel/checkpoints/ckpt_run-1"
    assert checkpoint.exists()

    resumed, requests = context(monkeypatch, [completion(calls=[("lookup", {"n": 2})])],
                                {"lookup": lookup}, high=True)
    resumed.resuming = True

    async def suspend_again(tool, kwargs):
        suspensions.append(kwargs["n"])
        resumed.pending = {"tool": tool, "kwargs": kwargs, "checkpoint_ref": "ckpt_run-1"}
        return resumed.suspend("approval", ref="approval-2")

    resumed.suspend_for_approval = suspend_again

    async def continue_loop():
        try:
            return await resumed.loop([{"role": "user", "content": "ignored"}], TOOLS)
        finally:
            await resumed.aclose()

    event = asyncio.run(continue_loop())
    assert event.reason.value == "approval"
    assert suspensions == [1, 2]
    assert seen == [1]
    assert requests[0]["messages"][-1]["role"] == "tool"
    assert json.loads(checkpoint.read_text())["pending_calls"][0]["arguments"] == {"n": 2}

    final, requests = context(monkeypatch, [completion("done")], {"lookup": lookup}, high=True)
    final.resuming = True

    async def finished():
        try:
            return await final.loop([{"role": "user", "content": "ignored"}], TOOLS)
        finally:
            await final.aclose()

    assert asyncio.run(finished()).answer == "done"
    assert seen == [1, 2]
    assert not checkpoint.exists()
