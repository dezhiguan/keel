from keel import Agent

agent = Agent.from_manifest("agent.yaml")


@agent.child("research-bot")
async def research(text, budget, trace_id):
    return "notes:" + text


@agent.child("draft-bot")
async def draft(text, budget, trace_id):
    return "draft:" + text


@agent.entry
async def run(request, ctx):
    research_result = await ctx.delegate("research-bot", request.input["text"])
    ctx.step("research-bot")
    draft_result = await ctx.delegate("draft-bot", research_result["result"])
    ctx.step("draft-bot")
    return ctx.final(
        draft_result["result"],
        meta={"trace_id": draft_result["trace_id"], "budget_cny": draft_result["budget"]["cny"]},
    )


app = agent.asgi()
