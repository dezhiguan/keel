from keel import Agent

from tools.citations import citations_from

agent = Agent.from_manifest("agent.yaml")


@agent.entry
async def run(request, ctx):
    question = request.input["text"]
    found = await ctx.knowledge.search("demo-kb", question)
    reply = await ctx.llm.chat([{"role": "user", "content": question}])
    cited = citations_from(found.citations or found.results)
    ctx.step("answer")
    return ctx.final(reply or "", citations=cited)


app = agent.asgi()
