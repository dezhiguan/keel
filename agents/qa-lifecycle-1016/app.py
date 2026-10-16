"""Lifecycle acceptance agent using the Keel loop, scoped RAG and an audited tool."""
import os

from keel import Agent
from keel.protocol.events import SuspendEvent

agent = Agent.from_manifest("agent.yaml")

TOOLS = [{
    "type": "function",
    "function": {
        "name": "shift.policy",
        "description": "查询故障、变更或咨询的值班处理口径。用户询问值班安排、响应流程或对应口径时调用。",
        "parameters": {
            "type": "object",
            "properties": {"category": {"type": "string", "enum": ["故障", "变更", "咨询"]}},
            "required": ["category"],
            "additionalProperties": False,
        },
    },
}]


@agent.entry
async def run(request, ctx):
    question = (request.input.get("text") or "").strip()
    if question == "probe":
        return ctx.final("probe ok")
    ids = [part.strip() for part in os.environ["KEEL_RAGFORGE_KB_IDS"].split(",") if part.strip()]
    if len(ids) != 1:
        raise RuntimeError("Expected exactly one scoped knowledge base")

    found = await ctx.knowledge.search(int(ids[0]), question, top_k=3)
    passages = []
    citations = []
    for hit, citation in zip(found.results, found.citations):
        content = hit.get("content") or hit.get("text")
        if content:
            passages.append(f"来源：{hit.get('filename') or '未命名文档'}\n{content}")
            citations.append(citation)

    prompt = await ctx.prompt("answer")
    messages = prompt.compile(question=question, passages="\n\n".join(passages) or "无相关知识库片段")
    result = await ctx.loop(messages, TOOLS, max_steps=4, prompt=prompt)
    if isinstance(result, SuspendEvent):
        return result
    answer = result.answer
    if (passages and "来源：" not in answer
            and not any(word in answer for word in ("知识库资料不足", "无法回答", "不能编造"))
            and not any(word in question for word in ("值班", "口径", "故障", "变更", "咨询", "/policy"))):
        source = next((hit.get("filename") for hit in found.results if hit.get("filename")), None)
        if source:
            answer = f"{answer.rstrip()}\n\n来源：{source}"
    return ctx.final(answer, citations=citations if "来源：" in answer else [])


app = agent.asgi()
