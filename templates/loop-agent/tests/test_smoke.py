import asyncio
import json
import os
from types import SimpleNamespace

import httpx

from keel.context import Context
from keel.llm.client import LlmClient

os.environ.setdefault("KEEL_LLM_BASE_URL", "https://gateway.test")
os.environ.setdefault("KEEL_LLM_KEY", "test-virtual-key")
os.environ.setdefault("KEEL_AUDIT_URL", "http://127.0.0.1:8001")
os.environ.setdefault("KEEL_AUDIT_TOKEN", "local-dev-token")
os.environ.setdefault("KEEL_AUDIT_SPOOL_PATH", ".keel/audit-spool.jsonl")
os.environ.setdefault("KEEL_ENV", "dev")

from app import agent, run


def _completion(content=None, tool=False):
    message = {"role": "assistant", "content": content}
    if tool:
        message["tool_calls"] = [{"id": "call-1", "type": "function",
                                  "function": {"name": "echo", "arguments": '{"text":"hello"}'}}]
    return {"id": "chatcmpl-smoke", "object": "chat.completion", "created": 1,
            "model": "qwen-plus", "choices": [{"index": 0, "message": message,
                                            "finish_reason": "tool_calls" if tool else "stop"}],
            "usage": {"prompt_tokens": 2, "completion_tokens": 2, "total_tokens": 4}}


class Audit:
    async def record(self, *args, **kwargs):
        pass

    async def aclose(self):
        pass


def test_default_agent_runs_a_tool_cycle_against_fake_gateway(monkeypatch):
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    responses = [_completion(tool=True), _completion("hello")]
    seen = []

    def gateway(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json=responses.pop(0))

    async def scenario():
        llm = LlmClient(agent.name, "qwen-plus", "trace-1", http_client=httpx.AsyncClient(
            transport=httpx.MockTransport(gateway)))
        ctx = Context(agent.name, "run-1", "trace-1", asyncio.Queue(), manifest=agent.manifest,
                      tool_functions=agent._tools, llm_client=llm, audit_reporter=Audit(), env="dev")
        try:
            return await run(SimpleNamespace(input={"text": "repeat hello"}), ctx)
        finally:
            await ctx.aclose()

    result = asyncio.run(scenario())
    assert result.answer == "hello"
    assert seen[0]["tools"][0]["function"]["name"] == "echo"
    assert seen[1]["messages"][-1] == {"role": "tool", "tool_call_id": "call-1", "content": '"hello"'}


def test_manifest_declares_agent_capabilities():
    assert agent.manifest.spec.kind.value == "agent"
    assert agent.manifest.spec.prompts.items[0].name == "assistant"
    assert agent.manifest.spec.eval.dataset.endswith("/smoke")
