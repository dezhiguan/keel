from keel import Agent

from tools.citations import citations_from

agent = Agent.from_manifest("agent.yaml")


@agent.entry
async def run(request, ctx):
    question = request.input["text"]
    passages = []
    cited = []
    for source in agent.manifest.spec.knowledge or []:
        found = await ctx.knowledge.search(source.kb, question)
        for hit, citation in zip(found.results, found.citations):
            content = hit.get("content") or hit.get("text")
            if content:
                passages.append(f"来源：{hit.get('filename') or '未命名文档'}\n{content}")
                cited.extend(citations_from([citation]))
    if not passages:
        return ctx.final("当前知识库没有检索到可支持回答的资料。", citations=[])
    prompt = await ctx.prompt("answer")
    compiled = prompt.compile(question=question, passages="\n\n".join(passages))
    messages = compiled if isinstance(compiled, list) else [{"role": "user", "content": compiled}]
    reply = await ctx.llm.chat(messages, prompt=prompt)
    ctx.step("answer")
    return ctx.final(reply or "", citations=cited)


app = agent.asgi()
