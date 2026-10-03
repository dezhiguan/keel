from keel import Agent
from keel.protocol.events import SuspendEvent

from tools.checkpoint import create_work_order

agent = Agent.from_manifest("agent.yaml")


@agent.tool
def echo(text: str) -> str:
    return text


@agent.tool(name="create_work_order")
def create_work_order_tool(title: str) -> str:
    return create_work_order(title)


@agent.entry
async def run(request, ctx):
    text = request.input["text"]
    if text.startswith("echo "):
        heard = await ctx.tools.call("echo", text=text.removeprefix("echo "))
        return ctx.final(heard)
    outcome = await ctx.tools.call("create_work_order", title=text)
    if isinstance(outcome, SuspendEvent):
        return outcome
    return ctx.final(outcome)


app = agent.asgi()
