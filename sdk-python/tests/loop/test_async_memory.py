import asyncio
import copy
from types import SimpleNamespace
import pytest
from keel.context_window import ContextWindow,tokens
from keel.llm.client import ChatResult
from keel.memory_maintenance import BoundedModel,WorkLimit,summary_prefix,summarize,extract_facts
from keel.session_facts import empty_state

class Memory:
    async def load_summary(self,owner):return {'version':0,'throughId':'0','content':'','maintenanceMode':'async'}
    async def load_facts(self,external):return {'state':empty_state(),'messages':[{'id':'1','content':'预算20万'}],'skippedIds':[],'nextCursor':'1','more':False}
    async def search_session(self,*args):return {'items':[]}
    async def read_session(self,*args):return {'items':[]}

class Model:
    tracer=None;trace_id='fixture';agent='fixture-agent'
    def __init__(self,reply):self.reply=reply;self.calls=0
    async def chat(self,messages,**kwargs):
        self.calls+=1
        assert kwargs['emit_tokens'] is False and kwargs['purpose']=='memory'
        return ChatResult(self.reply,(),100,20)

def job(kind='summary'):
    return dict(kind=kind,maxCalls=4,maxInputTokens=10000,maxOutputTokens=1600,maxCostCny=.5,env='staging',expectedVersion=0,
                state={'version':0,'throughId':'0','content':''},messages=[{'role':'user','content':'检查日期11月26日','id':'1'},{'role':'assistant','content':'收到','id':'2'}])

def test_async_hot_path_never_calls_model_and_preserves_correction_and_tool_chain(monkeypatch):
    monkeypatch.setenv('KEEL_MEMORY_MAINTENANCE_MODE','async');monkeypatch.setenv('KEEL_CONTEXT_TOKEN_BUDGET','10000')
    m=Model('should not call')
    w=ContextWindow(m,Memory(),'fixture-agent','staging','scope','owner',True,None,'trace')
    prior=[{'id':'1','role':'user','content':'填充'*6000},{'id':'2','role':'assistant','content':'旧回答'},
           {'id':'3','role':'user','content':'预算20万'},{'id':'4','role':'assistant','content':'收到'}]
    chain=[{'role':'user','content':'更正预算为30万'}, {'role':'assistant','content':'','tool_calls':[{'id':'a','type':'function','function':{'name':'read','arguments':'{}'}}]}, {'role':'tool','tool_call_id':'a','content':'当前结果'}]
    result=asyncio.run(w.assemble([{'role':'system','content':'规则'}],prior,chain,[]))
    assert m.calls==0 and tokens(result,[])<10000 and result[-3:]==chain
    assert w.summary_through_id=='2' and w.async_readonly
    assert any('预算20万'==r.get('content') for r in result)
    assert any('明确纠正' in r.get('content','') for r in result)

def test_long_current_chain_is_not_truncated(monkeypatch):
    monkeypatch.setenv('KEEL_MEMORY_MAINTENANCE_MODE','async')
    w=ContextWindow(Model('unused'),Memory(),'a','staging','s','u',True,None,'t')
    current=[{'role':'user','content':'测'*12000}]
    assert asyncio.run(w.assemble([],[],current,[]))==current

def test_batch_beyond_four_chunks_commits_only_complete_prefix():
    j=job();j['messages']=[]
    for i in range(8):j['messages'] += [{'role':'user','content':'材料'*1700,'id':str(2*i+1)}, {'role':'assistant','content':'收到','id':str(2*i+2)}]
    chunks,last=summary_prefix(j,10000)
    assert len(chunks)==4 and int(last)<16
    assert all(c[0]['role']=='user' and c[-1]['role']=='assistant' for c in chunks)
    j['messages']=[{'role':'user','content':'测'*11000,'id':'1'},{'role':'assistant','content':'收到','id':'2'}]
    with pytest.raises(WorkLimit,match='source_too_large'):summary_prefix(j,10000)

def test_summary_rejects_invented_references_and_empty_output():
    async def run():
        for text in ('','虚构 [m:999]'):
            model=BoundedModel(Model(text),job(),'0.00001','0.00003')
            with pytest.raises(ValueError):await summarize(model,job())
    asyncio.run(run())

def test_paid_work_bound_is_reserved_before_call_and_caps_retries():
    async def run():
        raw=Model('事实 [m:1]');j=job();j['maxCalls']=1
        model=BoundedModel(raw,j,'0.00001','0.00003')
        await model.chat([{'role':'user','content':'材料'}],tools=[],max_tokens=512)
        with pytest.raises(WorkLimit,match='budget'):await model.chat([{'role':'user','content':'材料'}],tools=[],max_tokens=512)
        assert raw.calls==1 and model.usage()['costUpperBoundCny']>0
    asyncio.run(run())

def test_facts_use_original_batch_and_preserve_expected_version():
    async def run():
        j=job('facts');j.update(state=empty_state(),skippedIds=[],nextCursor='1',more=False)
        j['messages']=j['messages'][:1]
        raw=Model('{"operations":[{"sourceId":"1","entity":"青岚","attribute":"检查日期","scope":"","value":"11月26日","quote":"检查日期11月26日","action":"assert","targetIds":[]}],"ignoredSourceIds":[]}')
        model=BoundedModel(raw,j,'0.00001','0.00003')
        state=await extract_facts(model,j)
        assert state['version']==0 and state['cursorId']=='1'
        assert state['facts'][0]['value']=='11月26日'
    asyncio.run(run())

def test_fast_recall_covers_newly_trimmed_prefix(monkeypatch):
    monkeypatch.setenv('KEEL_MEMORY_MAINTENANCE_MODE','async')
    seen=[]
    class RecallMemory(Memory):
        async def search_session(self,query,before,external):
            seen.append(before)
            return {'items':[{'id':'1','role':'user','content':'青岚代号LOTUS-9813','offset':0,'totalChars':20,'sha256':'fixture','hasMore':False}]}
    w=ContextWindow(Model('unused'),RecallMemory(),'a','staging','s','u',True,None,'t')
    prior=[{'id':'1','role':'user','content':'青岚代号LOTUS-9813'+'填'*11000},{'id':'2','role':'assistant','content':'收到'}, {'id':'3','role':'user','content':'近期问候'},{'id':'4','role':'assistant','content':'你好'}]
    result=asyncio.run(w.assemble([],prior,[{'role':'user','content':'青岚代号是什么'}],[]))
    assert seen==['3']
    assert any('LOTUS-9813' in r.get('content','') for r in result)

def test_worker_commits_separate_trace_and_closes_client(monkeypatch):
    from keel import memory_maintenance as worker
    from opentelemetry import trace
    monkeypatch.setenv('KEEL_MEMORY_MAX_INPUT_CNY_PER_TOKEN','.00001')
    monkeypatch.setenv('KEEL_MEMORY_MAX_OUTPUT_CNY_PER_TOKEN','.00003')
    clients=[]
    class Client(Model):
        def __init__(self,*args,**kwargs):super().__init__('摘要 [m:1]');self.trace_id=args[2];clients.append(self);self.closed=False
        async def aclose(self):self.closed=True
    monkeypatch.setattr(worker,'LlmClient',Client)
    j=job();j.update(id=1,leaseToken='lease',triggerTraceId='original-request',sessionId='scope',targetId='2',attempt=1)
    calls=[]
    class Remote:
        async def complete_maintenance(self,b):calls.append(b);return {'saved':True,'more':False}
    agent=SimpleNamespace(name='fixture-agent',manifest=SimpleNamespace(spec=SimpleNamespace(models=SimpleNamespace(default_='chat-default'))))
    asyncio.run(worker.execute(agent,Remote(),j,trace.get_tracer('fixture')))
    assert len(calls)==1 and calls[0]['traceId']!='original-request' and calls[0]['state']['throughId']=='2'
    assert calls[0]['costUpperBoundCny']>0 and clients[0].closed

def test_lease_contract_rejects_wrong_scope_and_unbounded_model_work():
    from keel.protocol.memory import MaintenanceJob
    from pydantic import ValidationError
    valid={**job(), 'id':1,'ownerUser':'u','sessionId':'a'*64,'targetId':'2','leaseToken':'b'*36,'triggerTraceId':'trace','attempt':1,'skippedIds':[],'nextCursor':'2','more':False}
    assert MaintenanceJob.model_validate(valid).model_dump(by_alias=True)['maxCalls']==4
    for change in ({'maxCalls':5},{'maxCostCny':float('nan')},{'sessionId':'another-session'},{'expectedVersion':True},{'kind':'other'}):
        with pytest.raises(ValidationError):MaintenanceJob.model_validate({**valid,**change})


def test_background_timeout_is_reported_as_timeout_without_advancing_facts():
    async def run():
        j=job('facts');j.update(state=empty_state(),skippedIds=[],nextCursor='1',more=False)
        j['messages']=j['messages'][:1]
        class TimeoutModel(Model):
            async def chat(self,*args,**kwargs):raise TimeoutError('controlled timeout')
        model=BoundedModel(TimeoutModel(''),j,'.000002','.000005')
        with pytest.raises(TimeoutError):await extract_facts(model,j)
        assert j['state']['cursorId']=='0' and model.calls==1
    asyncio.run(run())
