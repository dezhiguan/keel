import asyncio
import json

from keel import Agent
from keel.protocol.errors import ErrorCode
from tests.test_runtime import agent_file, call, frames


def test_delegate_shares_trace_and_spends_budget(tmp_path):
    path = agent_file(tmp_path)
    text = path.read_text() + "  delegates:\n    - research-bot\n    - draft-bot\n"
    path.write_text(text)
    agent = Agent.from_manifest(path)

    @agent.child("research-bot")
    async def research(text, budget, trace_id):
        return "notes:" + text

    @agent.child("draft-bot")
    async def draft(text, budget, trace_id):
        return "draft:" + text

    @agent.entry
    async def handle(req, ctx):
        first = await ctx.delegate("research-bot", req.input["text"])
        second = await ctx.delegate("draft-bot", first["result"])
        assert first["trace_id"] == second["trace_id"] == ctx.trace_id
        assert second["budget"]["steps"] == 6
        return ctx.final(second["result"])

    response = asyncio.run(call(
        agent.asgi(), "POST", "/v1/invoke",
        json={"input": {"text": "风机"}},
        headers={"X-Keel-Budget": json.dumps({"cny": 1.0, "steps": 8})},
    ))
    assert frames(response)[-1]["data"]["answer"] == "draft:notes:风机"


def test_undeclared_delegate_is_rejected(tmp_path):
    agent = Agent.from_manifest(agent_file(tmp_path))

    @agent.entry
    async def handle(req, ctx):
        await ctx.delegate("other-bot", "x")
        return ctx.final("nope")

    response = asyncio.run(call(agent.asgi(), "POST", "/v1/invoke", json={"input": {"text": "x"}}))
    frame = frames(response)[0]
    assert frame["event"] == "error"
    assert frame["data"]["code"] == ErrorCode.DELEGATE_NOT_DECLARED.value
