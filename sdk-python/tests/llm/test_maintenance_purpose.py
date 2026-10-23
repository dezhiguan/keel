"""HTTP fixture checks only; real gateway acceptance is recorded separately."""
import asyncio
import json
import httpx
import pytest
from keel.llm.client import LlmClient


def test_maintenance_marker_is_optional_and_governed_only(monkeypatch):
    monkeypatch.setenv('KEEL_MODE','governed')
    monkeypatch.setenv('KEEL_LLM_BASE_URL','https://gateway.test/v1')
    monkeypatch.setenv('KEEL_LLM_KEY','test-virtual-key')
    seen=[]
    def handler(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200,json={'id':'fixture','object':'chat.completion','created':1,'model':'fixture',
            'choices':[{'index':0,'message':{'role':'assistant','content':'ok'},'finish_reason':'stop'}],
            'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}})
    async def calls():
        client=LlmClient('agent','chat-default','trace',http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        try:
            await client.chat([{'role':'user','content':'maintenance'}],tools=[],emit_tokens=False,purpose='memory')
            await client.chat([{'role':'user','content':'normal'}],tools=[])
            with pytest.raises(ValueError):await client.chat([],purpose='other')
        finally:await client.aclose()
        monkeypatch.setenv('KEEL_MODE','standalone')
        client=LlmClient('agent','chat-default','trace',http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        try:await client.chat([{'role':'user','content':'local'}],tools=[],purpose='memory')
        finally:await client.aclose()
    asyncio.run(calls())
    assert seen[0]['metadata']=={'caller_agent':'agent','keel_task':'memory'}
    assert seen[1]['metadata']=={'caller_agent':'agent'}
    assert 'metadata' not in seen[2]
    assert all('thinking' not in r and 'enable_thinking' not in r for r in seen)
