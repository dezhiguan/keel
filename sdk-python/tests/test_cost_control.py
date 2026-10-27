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


def test_direct_context_loop_opens_once_propagates_and_closes_its_owned_task(monkeypatch):
    from unittest.mock import AsyncMock
    from keel.context import Context
    from keel.llm.client import ChatResult
    value=scope(contextDedupe=True)
    opened=AsyncMock(return_value=value);bound=AsyncMock();finished=AsyncMock()
    monkeypatch.setattr(cost,'establish',opened);monkeypatch.setattr(cost,'bind_trace',bound);monkeypatch.setattr(cost,'finish',finished)
    class Model:
        async def chat(self,*args,**kwargs):
            assert cost.current.get()==value
            return ChatResult('真实协议夹具响应',())
        async def aclose(self):pass
    async def run():
        ctx=Context('fixture','run','a'*32,asyncio.Queue(),env='staging',llm_client=Model(),eval_run_id='eval-fixture')
        one=await ctx.loop([{'role':'user','content':'问题'}],[],max_steps=1)
        two=await ctx.loop([{'role':'user','content':'追问'}],[],max_steps=1)
        assert one.meta['taskCost']['taskId']==two.meta['taskCost']['taskId']==value['taskId']
        assert value['ticket'] not in json.dumps(one.meta)
        await ctx.aclose()
    asyncio.run(run())
    assert opened.await_count==bound.await_count==finished.await_count==1
    assert finished.call_args.args[3]=='succeeded'
    assert cost.current.get() is None


def test_direct_context_llm_uses_the_same_managed_task_provider(monkeypatch):
    from unittest.mock import AsyncMock
    from keel.context import Context
    value=scope();monkeypatch.setenv('KEEL_LLM_BASE_URL','http://gateway/v1');monkeypatch.setenv('KEEL_LLM_KEY','fixture-key')
    opened=AsyncMock(return_value=value);monkeypatch.setattr(cost,'establish',opened);monkeypatch.setattr(cost,'bind_trace',AsyncMock());monkeypatch.setattr(cost,'finish',AsyncMock())
    requests=[]
    def handler(request):
        requests.append(json.loads(request.content));return httpx.Response(200,json={'id':'test','choices':[{'message':{'content':'答复','role':'assistant'},'finish_reason':'stop','index':0}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}})
    async def run():
        client=LlmClient('fixture','model','a'*32,http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        ctx=Context('fixture','run','a'*32,asyncio.Queue(),env='staging',llm_client=client)
        await ctx.llm.chat([{'role':'user','content':'问题'}],emit_tokens=False)
        ctx.final('答复');await ctx.aclose()
    asyncio.run(run())
    assert opened.await_count==1
    assert requests[0]['metadata']['keel_cost_ticket']==value['ticket']


def test_inherited_root_is_not_reopened_or_completed_by_a_child_context(monkeypatch):
    from unittest.mock import AsyncMock
    from keel.context import Context
    from keel.llm.client import ChatResult
    opened=AsyncMock();finished=AsyncMock();monkeypatch.setattr(cost,'establish',opened);monkeypatch.setattr(cost,'finish',finished)
    class Model:
        async def chat(self,*args,**kwargs):return ChatResult('子步骤响应',())
        async def aclose(self):pass
    async def run():
        with cost.bind(scope()):
            ctx=Context('fixture','child','a'*32,asyncio.Queue(),env='staging',llm_client=Model())
            await ctx.loop([{'role':'user','content':'问题'}],[],max_steps=1);await ctx.aclose()
    asyncio.run(run());opened.assert_not_awaited();finished.assert_not_awaited()


def test_gateway_scope_denial_remains_a_non_retryable_registered_error(monkeypatch):
    from keel.protocol.errors import ErrorCode
    monkeypatch.setenv('KEEL_LLM_BASE_URL','http://gateway/v1');monkeypatch.setenv('KEEL_LLM_KEY','fixture-key')
    requests=[]
    def handler(request):
        requests.append(request);return httpx.Response(403,json={'code':'COST_SCOPE_DENIED','message':'scope rejected','retryable':False})
    async def run():
        client=LlmClient('fixture','model','a'*32,http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        with pytest.raises(KeelError) as caught:await client.chat([{'role':'user','content':'问题'}])
        assert caught.value.code==ErrorCode.COST_SCOPE_DENIED
        await client.aclose()
    asyncio.run(run());assert len(requests)==1
