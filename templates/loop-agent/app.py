from keel import Agent

agent = Agent.from_manifest("agent.yaml")

TOOLS = [{"type": "function", "function": {
    "name": "echo", "description": "Repeat text to the caller",
    "parameters": {"type": "object", "properties": {"text": {"type": "string"}},
                   "required": ["text"]},
}}]


@agent.tool
def echo(text: str) -> str:
    return text


@agent.entry
async def run(request, ctx):
    return await ctx.loop([{"role": "user", "content": request.input["text"]}], TOOLS)


app = agent.asgi()
