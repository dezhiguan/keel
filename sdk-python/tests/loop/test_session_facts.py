import asyncio
import copy
import json
from concurrent.futures import ThreadPoolExecutor
import pytest
from keel.lite.store import LiteStore
from keel.lite.memory import LiteMemory
from keel.llm.client import ChatResult
from keel.session_facts import SessionFacts, apply, empty_state


def op(source, value, action='assert', targets=None, entity='纸鹤计划', scope=''):
    text = ('更正为' if action=='correct' else '')+value
    return dict(sourceId=source, entity=entity, attribute='检查日期', scope=scope,
                value=value, quote=text, action=action, targetIds=targets or [])


def test_conflicts_do_not_pick_latest_and_confirmation_keeps_replacement_chain():
    state=empty_state();sources={'1':'12月9日','2':'12月10日','3':'选12月9日','4':'更正为12月11日'}
    apply(state,op('1','12月9日'),sources)
    first=state['facts'][0]['id']
    apply(state,op('2','12月10日'),sources)
    assert [f['status'] for f in state['facts']]==['pending','pending']
    confirm=op('3','12月9日','confirm',[first]);confirm['quote']='选12月9日'
    apply(state,confirm,sources)
    assert [f['status'] for f in state['facts']]==['active','superseded']
    old=state['facts'][1]
    assert old['resolvedBy']==first and old['id'] in state['facts'][0]['replaces']
    apply(state,op('4','12月11日','correct'),sources)
    assert [f['status'] for f in state['facts']]==['superseded','superseded','active']
    assert first in state['facts'][2]['replaces']


def test_entity_time_and_condition_are_separate_and_repeats_are_idempotent():
    state=empty_state();sources={'1':'12月9日','2':'12月10日','3':'12月11日'}
    for item in [op('1','12月9日',scope='今年'),op('2','12月10日',scope='明年'),op('3','12月11日',entity='白鹭计划',scope='今年')]:
        apply(state,item,sources);apply(state,item,sources)
    assert len(state['facts'])==3 and all(f['status']=='active' for f in state['facts'])


def test_denial_keeps_history_and_ambiguous_confirmation_does_not_choose():
    state=empty_state();sources={'1':'12月9日','2':'撤销12月9日','3':'是'}
    apply(state,op('1','12月9日'),sources)
    with pytest.raises(ValueError):
        apply(state,{**op('3','12月9日','confirm'), 'quote':'是'},sources)
    denial=op('2','12月9日','deny',[state['facts'][0]['id']]);denial['quote']='撤销12月9日'
    apply(state,denial,sources)
    assert state['facts'][0]['status']=='retracted' and state['facts'][0]['sourceIds']==['1','2']


@pytest.mark.parametrize('change',[
    {'sourceId':'999'}, {'quote':'伪造证据'}, {'value':'不存在的值'}, {'targetIds':['nonexistent']},
    {'action':'latest-wins'}, {'scope':None},
])
def test_invalid_model_proposals_are_rejected(change):
    with pytest.raises(ValueError):apply(empty_state(),{**op('1','12月9日'),**change},{'1':'12月9日'})


def test_current_fact_is_ephemeral_then_replayed_only_from_committed_user_original(tmp_path):
    memory=LiteMemory(LiteStore(tmp_path),'a','s')
    memory.append('user','12月9日');memory.append('assistant','助手幻觉12月20日')
    class Model:
        async def chat(self,messages,**kw):
            data=json.loads(messages[-1]['content'])
            assert '助手幻觉' not in messages[-1]['content']
            proposals=[op(k,v) for k,v in data['用户原文'].items()]
            return ChatResult(json.dumps({'operations':proposals},ensure_ascii=False),())
    current=[{'role':'user','content':'更正为12月11日'}]
    class CorrectionModel(Model):
        async def chat(self,messages,**kw):
            data=json.loads(messages[-1]['content'])
            proposals=[op(k,'12月11日' if v.startswith('更正') else v,'correct' if v.startswith('更正') else 'assert') for k,v in data['用户原文'].items()]
            return ChatResult(json.dumps({'operations':proposals},ensure_ascii=False),())
    result=asyncio.run(SessionFacts(CorrectionModel(),memory,False,'a','staging').collect(current))
    view=json.loads(result[-1]['content'].split('\n',1)[1])
    assert [f['status'] for f in view['facts']]==['superseded','active']
    stored=asyncio.run(memory.load_facts(False))['state']
    assert len(stored['facts'])==1 and stored['facts'][0]['status']=='active'
    assert 'current' not in json.dumps(stored)
    memory.append('user','更正为12月11日')
    asyncio.run(SessionFacts(CorrectionModel(),memory,False,'a','staging').collect([{'role':'user','content':''}]))
    stored=asyncio.run(memory.load_facts(False))['state']
    assert [f['status'] for f in stored['facts']]==['superseded','active']


def test_sqlite_cas_has_one_winner_and_owner_session_ttl_isolation(tmp_path):
    store=LiteStore(tmp_path)
    store.append_session_message('a','s','user','12月9日',100,100)
    state=empty_state();state['cursorId']='1';apply(state,op('1','12月9日'),{'1':'12月9日'})
    with ThreadPoolExecutor(2) as pool:
        results=list(pool.map(lambda _:store.save_session_facts('a','s',state,101,99),range(2)))
    assert sum(r['saved'] for r in results)==1
    assert all(r['state']['version']==1 for r in results)
    assert store.session_facts('b','s',101)['state']['version']==0
    assert store.session_facts('a','other',101)['state']['version']==0
    assert store.session_facts('a','s',201)['state']['version']==0


def test_oversized_original_is_not_truncated_and_gap_is_explicit(tmp_path):
    store=LiteStore(tmp_path)
    store.append_session_message('a','s','user','填'*7000,100,100)
    store.append_session_message('a','s','user','12月9日',100,100)
    batch=store.session_facts('a','s',101)
    assert batch['skippedIds']==['1'] and batch['messages'][0]['id']=='2'
    assert store.read_session('a','s','1',101)['items'][0]['totalChars']==7000


def test_invalid_extraction_does_not_advance_cursor_or_break_chat(tmp_path):
    memory=LiteMemory(LiteStore(tmp_path),'a','s');memory.append('user','12月9日')
    class Model:
        async def chat(self,*args,**kwargs):return ChatResult('not JSON',())
    assert asyncio.run(SessionFacts(Model(),memory,False,'a','staging').collect([]))==[]
    assert asyncio.run(memory.load_facts(False))['state']['cursorId']=='0'


def test_cas_loser_reextracts_against_canonical_state(tmp_path):
    base=LiteMemory(LiteStore(tmp_path),'a','s');base.append('user','12月9日')
    class Memory:
        guard=None
        def __init__(self):self.writes=0
        async def load_facts(self,external):return await base.load_facts(False)
        async def save_facts(self,state,external):
            self.writes+=1
            result=await base.save_facts(state,False)
            return {**result,'saved':False} if self.writes==1 else result
    class Model:
        calls=0
        async def chat(self,messages,**kw):
            self.calls+=1
            rows=json.loads(messages[-1]['content'])['用户原文']
            return ChatResult(json.dumps({'operations':[op(k,v) for k,v in rows.items()]}),())
    model=Model();memory=Memory()
    result=asyncio.run(SessionFacts(model,memory,True,'a','staging').collect([]))
    assert model.calls==2 and result and memory.writes==1
    assert len(asyncio.run(base.load_facts(False))['state']['facts'])==1


def test_semantic_memory_span_uses_runtime_provider_and_contains_only_evidence(tmp_path):
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
    provider=TracerProvider();exporter=InMemorySpanExporter();provider.add_span_processor(SimpleSpanProcessor(exporter))
    memory=LiteMemory(LiteStore(tmp_path),'a','s');memory.append('user','12月9日')
    class Model:
        tracer=provider.get_tracer('runtime')
        async def chat(self,messages,**kw):
            return ChatResult(json.dumps({'operations':[op('1','12月9日')]}),())
    asyncio.run(SessionFacts(Model(),memory,False,'a','staging').collect([]))
    spans=exporter.get_finished_spans()
    assert len(spans)==1 and spans[0].name=='memory.facts'
    data=json.loads(spans[0].attributes['keel.memory.evidence'])
    assert data['version']==1 and data['sourceIds']==['1']
    assert '12月9日' not in json.dumps(dict(spans[0].attributes),ensure_ascii=False)
    provider.shutdown()
