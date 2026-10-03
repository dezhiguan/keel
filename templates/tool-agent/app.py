from keel import Agent

from tools.checkpoint import create_work_order, save_checkpoint

agent = Agent.from_manifest("agent.yaml")


@agent.tool
def echo(text: str) -> str:
    return text


@agent.tool
def create_work_order_tool(title: str) -> str:
    return create_work_order(title)


@agent.entry
async def run(request, ctx):
    text = request.input["text"]
    if text.startswith("echo "):
        heard = await ctx.tools.call("echo", text=text.removeprefix("echo "))
        return ctx.final(heard)
    # High-risk work stops here. Resume executes only after a person approves.
    ref = save_checkpoint({"tool": "create_work_order", "title": text})
    return ctx.suspend("approval", ref=ref, prompt="创建工单?")


app = agent.asgi()
