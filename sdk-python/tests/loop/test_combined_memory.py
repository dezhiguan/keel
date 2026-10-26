import asyncio
import copy
import json
from types import SimpleNamespace
import pytest
from opentelemetry import trace
from keel import memory_maintenance as worker
from keel.memory_combined import prepare, validate_facts
from keel.session_facts import empty_state
from keel.llm.client import ChatResult


def jobs():
    common=dict(env='staging',ownerUser='u',sessionId='a'*64,maxCalls=4,maxInputTokens=10000,maxOutputTokens=1600,maxCostCny=.5,
                expectedVersion=0,leaseToken='lease-token',triggerTraceId='trigger',attempt=1,skippedIds=[],more=False)
    summary=dict(common,id=1,kind='summary',targetId='2',nextCursor='2',state={'version':0,'throughId':'0','content':''},
                 messages=[{'id':'1','role':'user','content':'青岚预算20万元'},{'id':'2','role':'assistant','content':'收到'}])
    facts=dict(common,id=2,kind='facts',targetId='3',nextCursor='3',state=empty_state(),
               messages=[{'id':'1','role':'user','content':'青岚预算20万元'},{'id':'3','role':'user','content':'更正青岚预算为30万元'}])
    return summary,facts


def output():
    def op(source,value,action,quote):return dict(sourceId=source,entity='青岚',attribute='预算',scope='',value=value,quote=quote,action=action,targetIds=[])
    return {'summary':'青岚原预算20万元 [m:1]', 'facts':{'operations':[op('1','20万元','assert','青岚预算20万元'),op('3','30万元','correct','更正青岚预算为30万元')],'ignoredSourceIds':[]}}


@pytest.mark.parametrize('broken,good',[(None,{'summary','facts'}),('summary',{'facts'}),('facts',{'summary'}),('root',set()),('timeout',set())])
@pytest.mark.parametrize('reverse',[False,True])
def test_one_paid_call_two_independent_results_and_no_fee_double_count(monkeypatch,broken,good,reverse):
    monkeypatch.setenv('KEEL_MEMORY_MAX_INPUT_CNY_PER_TOKEN','.000002')
    monkeypatch.setenv('KEEL_MEMORY_MAX_OUTPUT_CNY_PER_TOKEN','.000005')
    clients=[]
    class Client:
        def __init__(self,*args,**kwargs):self.tracer=trace.get_tracer('test');self.trace_id='bg';self.calls=0;self.closed=False;clients.append(self)
        async def chat(self,messages,**kwargs):
            self.calls+=1;assert kwargs['purpose']=='memory' and kwargs['max_tokens']==1600
            if broken=='timeout':raise TimeoutError()
            value=output()
            if broken=='summary':value['summary']='虚构 [m:999]'
            if broken=='facts':value['facts']['operations'][1]['value']='40万元'
            return ChatResult('bad-json' if broken=='root' else json.dumps(value,ensure_ascii=False),(),200,150)
        async def aclose(self):self.closed=True
    monkeypatch.setattr(worker,'LlmClient',Client)
    done=[];failed=[]
    class Remote:
        async def complete_maintenance(self,b):done.append(b);return {'saved':True,'more':False}
        async def fail_maintenance(self,b):failed.append(b);return {'accepted':True}
    a,b=jobs();pair=(b,a) if reverse else (a,b)
    agent=SimpleNamespace(name='test',manifest=SimpleNamespace(spec=SimpleNamespace(models=SimpleNamespace(default_='chat-default'))))
    asyncio.run(worker.execute_combined(agent,Remote(),*pair,trace.get_tracer('test')))
    assert clients[0].calls==1 and clients[0].closed
    assert {('summary' if row['id']==1 else 'facts') for row in done}==good
    assert len(done)+len(failed)==2
    assert sum(row['calls'] for row in done+failed)==1
    assert len({row['traceId'] for row in done+failed})==1
    assert sum(row['costUpperBoundCny'] for row in done+failed)>0
    if 'facts' in good:
        state=next(row['state'] for row in done if row['id']==2)
        assert state['cursorId']=='3' and {f['value']:f['status'] for f in state['facts']}=={'20万元':'superseded','30万元':'active'}


def test_scope_input_and_complete_prefix_bounds():
    a,b=jobs()
    with pytest.raises(ValueError):prepare(a,{**b,'sessionId':'b'*64},worker.summary_prefix)
    b['messages'].append({'id':'5','role':'user','content':'填'*8000})
    assert prepare(a,b,worker.summary_prefix) is None
    a,b=jobs();a['messages']*=1
    for n in range(5,25,2):a['messages'] += [{'id':str(n),'role':'user','content':'填'*1800},{'id':str(n+1),'role':'assistant','content':'收到'}]
    prepared=prepare(a,b,worker.summary_prefix)
    if prepared:
        assert int(prepared[-1])<24 and prepared[-2][-1]['role']=='assistant'


def test_combined_facts_keep_conflicts_reject_invented_and_incomplete_sources():
    a,b=jobs();p=output()['facts'];p['operations'][1]['action']='assert'
    state=validate_facts(p,b)
    assert all(f['status']=='pending' for f in state['facts'])
    for invalid in ({'operations':p['operations'][:1]}, {'operations':[{**p['operations'][0],'sourceId':'2'}],'ignoredSourceIds':['1','3']}):
        with pytest.raises(ValueError):validate_facts(invalid,b)
    assert b['state']==empty_state()


def test_combined_facts_never_accept_assistant_or_summary_as_evidence():
    a,b=jobs();p=output()['facts'];p['operations'][0]['quote']='收到'
    with pytest.raises(ValueError):validate_facts(p,b)


def test_shared_limiter_bounds_parallel_fallback_calls():
    async def run():
        active=0;peak=0
        class Client:
            tracer=None;trace_id='test'
            async def chat(self,*args,**kwargs):
                nonlocal active,peak
                active+=1;peak=max(peak,active)
                await asyncio.sleep(.01);active-=1
                return ChatResult('ok',(),5,1)
        limiter=asyncio.Semaphore(1)
        models=[worker.BoundedModel(Client(),jobs()[0],'.000002','.000005',limiter) for _ in range(4)]
        await asyncio.gather(*(m.chat([{'role':'user','content':'test'}],max_tokens=10) for m in models))
        assert peak==1 and sum(m.calls for m in models)==4
    asyncio.run(run())


def test_bare_confirmation_cannot_become_an_asserted_fact():
    a,b=jobs();b['messages']=[{'id':'3','role':'user','content':'是。'}];b['nextCursor']='3'
    proposal={'operations':[dict(sourceId='3',entity='用户',attribute='确认',scope='',value='是',quote='是',action='assert',targetIds=[])],'ignoredSourceIds':[]}
    with pytest.raises(ValueError,match='ambiguous standalone'):validate_facts(proposal,b)
    assert validate_facts({'operations':[],'ignoredSourceIds':['3']},b)['facts']==[]
