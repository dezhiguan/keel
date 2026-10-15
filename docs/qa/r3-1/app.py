from keel import Agent

agent = Agent.from_manifest("agent.yaml")
ANSWERS = {"repeat hello": "hello"}


@agent.entry
async def run(request, ctx):
    text = request.input.get("text", "")
    ctx.step("answer")
    return ctx.final(ANSWERS.get(text, text))


app = agent.asgi()
