from keel import Agent
from keel.protocol.events import SuspendEvent

agent = Agent.from_manifest("agent.yaml")


@agent.tool(name="export_report")
def export_report(text: str) -> str:
    return "exported:" + text


@agent.entry
async def run(request, ctx):
    outcome = await ctx.tools.call("export_report", text=request.input.get("text", ""))
    if isinstance(outcome, SuspendEvent):
        return outcome
    return ctx.final(str(outcome))


app = agent.asgi()
