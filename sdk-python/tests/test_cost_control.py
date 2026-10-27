import asyncio
import json
import pytest
import httpx
from keel import cost_control as cost
from keel.llm.client import LlmClient
from keel.prompts import TrustedMessage
from keel.protocol.errors import KeelError


def scope(**features):
    return dict(taskId='ct_'+'a'*32,ticket='tc_'+'b'*64,policyVersion='platform:*@1',mode='enforce',features=features)


def test_ticket_is_private_and_nested_bindings_are_restored():
    value=scope(contextDedupe=True)
    with cost.bind(value):
        assert cost.current.get()==value
        assert 'ticket' not in json.dumps(cost.public(value))
        with cost.bind(None):assert cost.current.get() is None
        assert cost.current.get()==value
    assert cost.current.get() is None
    with pytest.raises(KeelError):
        with cost.bind({'taskId':'invented'}):pass


def test_deduplication_preserves_originals_and_tool_exchanges():
    system=TrustedMessage({'role':'system','content':'规则'})
    chain=[system,system,{'role':'user','content':'规则'},{'role':'assistant','tool_calls':[{'id':'a'}]},
           {'role':'tool','tool_call_id':'a','content':'材料'}]
    with cost.bind(scope(contextDedupe=True)):
        result=cost.prepare(chain)
    assert result==[system,*chain[2:]] and result[0] is system
    assert len(chain)==5
    assert cost.prepare(chain) is chain


def test_model_wire_has_context_but_generation_input_does_not_and_no_client_retry(monkeypatch):
    monkeypatch.setenv('KEEL_LLM_BASE_URL','http://gateway/v1')
    monkeypatch.setenv('KEEL_LLM_KEY','fixture-key')
    requests=[]
    def handler(request):
        requests.append(json.loads(request.content))
        return httpx.Response(503,json={'error':{'message':'failure'}})
    async def run():
        client=LlmClient('fixture','model','0'*32,http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        with cost.bind(scope()):
            with pytest.raises(KeelError):await client.chat([{'role':'user','content':'问题'}],purpose='classify')
        await client.aclose()
    asyncio.run(run())
    assert len(requests)==1
    wire=requests[0]
    assert wire['metadata']['keel_cost_ticket']==scope()['ticket']
    assert wire['metadata']['keel_cost_purpose']=='classify'
    assert scope()['ticket'] not in json.dumps(wire['messages'])


def test_local_entry_has_explicit_no_coverage_without_service_identity(monkeypatch):
    monkeypatch.delenv('KEEL_SERVER_URL',raising=False)
    assert asyncio.run(cost.establish('fixture','staging',None,'text',None)) is None
    assert cost.public(None)=={'coverage':'not-connected'}
