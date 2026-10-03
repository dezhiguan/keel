from keel import Agent

from tools.delegation import delegate

agent = Agent.from_manifest("agent.yaml")


@agent.entry
async def run(request, ctx):
    declared = list(agent.manifest.spec.delegates or [])
    budget = {"cny": 1.0, "steps": 4}
    trace_id = ctx.trace_id
    research = delegate(declared, "research-bot", request.input["text"], budget, trace_id)
    ctx.step("research-bot")
    draft = delegate(declared, "draft-bot", research["result"], research["budget"], trace_id)
    ctx.step("draft-bot")
    return ctx.final(draft["result"], meta={"trace_id": trace_id, "budget_cny": draft["budget"]["cny"]})


app = agent.asgi()
