"""Private classifier output must not escape, execute tools, or bypass validation."""
import asyncio
import copy
import json
from pathlib import Path

import httpx
import pytest
import jsonschema

from keel.llm.client import LlmClient
from keel.protocol.errors import KeelError
from keel.routing import routing_schema, validate_routing

SCHEMA={'type':'object','additionalProperties':False,'required':['intent'],
        'properties':{'intent':{'type':'string','enum':['chat','retrieve_history']}}}


@pytest.mark.parametrize('content,finish,extra,valid',[
    ('{"intent":"chat"}','stop',{},True),
    ('{"intent":"release"}','stop',{},False),
    ('{"intent":"chat","authorization":"forged"}','stop',{},False),
    ('{"intent":"chat"','length',{},False),
    ('{"intent":"chat"}','stop',{'refusal':'refused'},False),
    ('{"intent":"chat"}','tool_calls',{'tool_calls':[{'id':'x','type':'function','function':{'name':'release','arguments':'{}'}}]},False),
    ('{"intent":"chat","intent":"retrieve_history"}','stop',{},False),
    ('{"intent":NaN}','stop',{},False),
])
def test_classification_is_private_and_locally_validated(monkeypatch,content,finish,extra,valid):
    monkeypatch.setenv('KEEL_LLM_BASE_URL','https://gateway.fixture.invalid/v1')
    monkeypatch.setenv('KEEL_LLM_KEY','fixture-virtual-key')
    seen=[];tokens=[]
    def handler(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200,json={'id':'fixture','object':'chat.completion','created':1,'model':'fixture',
            'choices':[{'index':0,'finish_reason':finish,'message':{'role':'assistant','content':content,**extra}}]})
    async def run():
        c=LlmClient('assistant','chat-default','trace',on_token=tokens.append,
                    http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        try:
            if valid:
                assert await c.chat([{'role':'user','content':'question'}],response_schema=SCHEMA)==content
            else:
                with pytest.raises(KeelError):
                    await c.chat([{'role':'user','content':'question'}],response_schema=SCHEMA)
            with pytest.raises(ValueError):
                await c.chat([],tools=[],response_schema=SCHEMA)
        finally:
            await c.aclose()
    asyncio.run(run())
    assert len(seen)==1 and not tokens
    assert not seen[0].get('stream') and 'tools' not in seen[0]
    assert seen[0]['response_format']['json_schema']['strict'] is True


def route():
    p=Path(__file__).resolve().parents[3]/'contracts/tests/fixtures/intent-routing/valid/task-query.json'
    return json.loads(p.read_text())


def test_evidence_rejects_authority_and_has_no_schema_mutation():
    value=route()
    assert validate_routing(value)['plan']['mode']=='local_fast'
    value['intent']['slots']['authorization']='not-authority'
    with pytest.raises(jsonschema.ValidationError):validate_routing(value)
    schema=routing_schema();schema['properties'].clear()
    assert routing_schema()['properties']


@pytest.mark.parametrize('kind',['duplicate','cycle','missing'])
def test_dependency_graph_is_validated_before_execution(kind):
    value=route()
    step={'step_id':'read','intent':'retrieve_state','operation':'list','mode':'local_slow','executor':'self',
          'depends_on':[],'status':'planned'}
    value['plan']['steps']=[step]
    if kind=='duplicate':value['plan']['steps'].append(copy.deepcopy(step))
    if kind=='cycle':step['depends_on']=['read']
    if kind=='missing':step['depends_on']=['unknown']
    with pytest.raises(ValueError):validate_routing(value)


def test_nonexecuting_decision_cannot_name_executor():
    value=route();value['plan']['mode']='deny'
    with pytest.raises(ValueError):validate_routing(value)


def test_error_metadata_preserves_validated_route_without_arbitrary_exception_data():
    from keel.protocol.errors import ErrorCode
    error=KeelError(ErrorCode.TOOL_NOT_GRANTED,meta={'routing':route(),'secret':'must-drop'})
    assert error.meta['routing']['plan']['mode']=='local_fast'
    assert 'secret' not in error.meta
    bad=route();bad['intent']['slots']['authorization']='must-drop'
    error=KeelError(ErrorCode.TOOL_NOT_GRANTED,meta={'routing':bad})
    assert error.meta=={} and error.code==ErrorCode.TOOL_NOT_GRANTED
