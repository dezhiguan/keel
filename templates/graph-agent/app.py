from keel import Agent

from tools.checkpoint import advance, load, start

agent = Agent.from_manifest("agent.yaml")


@agent.entry
async def run(request, ctx):
    text = request.input["text"]
    if text.startswith("resume "):
        state = advance(load(text.removeprefix("resume ")))
    else:
        state = advance(start(text))
    if state["step"] != "done":
        return ctx.suspend("input_required", ref=state["ref"], prompt="继续起草?")
    return ctx.final(state["notes"][-1])


app = agent.asgi()
