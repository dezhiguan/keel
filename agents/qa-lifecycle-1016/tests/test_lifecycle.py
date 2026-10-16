import asyncio
import json
import shutil
from pathlib import Path
from types import SimpleNamespace

import httpx

from keel.context import Context
from keel.llm.client import LlmClient


ROOT = Path(__file__).resolve().parents[1]


def _completion(content=None, tool=None):
    message = {"role": "assistant", "content": content}
    if tool:
        message["tool_calls"] = [{"id": "call-1", "type": "function", "function": {
            "name": "shift.policy", "arguments": json.dumps({"category": "故障"}, ensure_ascii=False)}}]
    return {"id": "qa-test", "object": "chat.completion", "created": 1, "model": "qwen-plus",
            "choices": [{"index": 0, "message": message,
                         "finish_reason": "tool_calls" if tool else "stop"}],
            "usage": {"prompt_tokens": 8, "completion_tokens": 8, "total_tokens": 16}}


class Knowledge:
    async def search(self, kb, question, top_k):
        assert kb == 123
        return SimpleNamespace(results=[{"content": "关键词检索基于倒排索引", "filename": "FAQ.txt"}],
                               citations=[{"filename": "FAQ.txt"}])

    async def aclose(self):
        pass


class Audit:
    async def record(self, *args, **kwargs):
        pass

    async def aclose(self):
        pass


def test_natural_language_tool_cycle_and_session_history(monkeypatch, tmp_path):
    monkeypatch.chdir(ROOT)
    monkeypatch.syspath_prepend(str(ROOT))
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-key")
    monkeypatch.setenv("KEEL_RAGFORGE_KB_IDS", "123")
    monkeypatch.setenv("KEEL_LOCAL_PROMPTS", "1")
    for key in ("KEEL_RAGFORGE_URL", "KEEL_RAGFORGE_TOKEN", "KEEL_AUDIT_URL",
                "KEEL_AUDIT_TOKEN", "KEEL_AUDIT_SPOOL_PATH", "KEEL_ENV",
                "KEEL_SERVER_URL", "KEEL_SERVICE_AUDIENCE", "KEEL_CLIENT_PRIVATE_KEY"):
        monkeypatch.setenv(key, "test")
    import app
    shutil.copytree(ROOT / "prompts", tmp_path / "prompts")
    monkeypatch.chdir(tmp_path)

    responses = [_completion(tool=True), _completion("故障值班先确认影响范围"),
                 _completion("刚才讨论的是故障值班"),
                 _completion("FAQ 文件是 05_常见问题FAQ.txt")]
    requests = []

    def gateway(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=responses.pop(0))

    async def turn(text, run_id):
        llm = LlmClient(app.agent.name, "qwen-plus", run_id,
                        http_client=httpx.AsyncClient(transport=httpx.MockTransport(gateway)))
        ctx = Context(app.agent.name, run_id, run_id, asyncio.Queue(), manifest=app.agent.manifest,
                      llm_client=llm, knowledge_client=Knowledge(), audit_reporter=Audit(),
                      session_id="qa-session", env="dev")
        async def tool(name, **kwargs):
            assert name == "shift.policy" and kwargs == {"category": "故障"}
            return {"category": "故障", "policy": "先确认影响范围"}
        ctx.tools.call = tool
        try:
            return await app.run(SimpleNamespace(input={"text": text}), ctx)
        finally:
            await ctx.aclose()

    assert "故障" in asyncio.run(turn("故障值班口径是什么？", "run-1")).answer
    assert "刚才" in asyncio.run(turn("刚才讨论了什么？", "run-2")).answer
    assert "来源：FAQ.txt" in asyncio.run(turn("平台基准库的 FAQ 文件叫什么？", "run-3")).answer
    assert requests[0]["tools"][0]["function"]["name"] == "shift.policy"
    assert requests[1]["messages"][-1]["role"] == "tool"
    assert any(row.get("content") == "故障值班先确认影响范围" for row in requests[2]["messages"])
    assert requests[2]["messages"][0]["role"] == "system"
