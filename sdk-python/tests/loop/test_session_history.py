import asyncio
import json
import pytest
from keel.context_window import ContextWindow, tokens
from keel.lite.memory import LiteMemory
from keel.lite.store import LiteStore
from keel.llm.client import ChatResult
from keel.session_history import source_ids


def test_original_beyond_128_messages_is_retrieved_within_budget(tmp_path, monkeypatch):
    monkeypatch.setenv('KEEL_CONTEXT_TOKEN_BUDGET','10000')
    memory=LiteMemory(LiteStore(tmp_path),'agent','session')
    memory.append('user','银杉项目的验收口令为 IRIS-7419，日期11月6日。')
    memory.append('assistant','已记录银杉项目约定。')
    for i in range(140):
        memory.append('user',f'最近的无关记录第{i}项。')
    prior=memory.context_history()
    assert len(prior)==128 and all('IRIS-7419' not in r['content'] for r in prior)
    window=ContextWindow(None,memory,'agent','staging','session',None,False,None,'trace')
    current=[{'role':'user','content':'银杉项目的验收口令是什么？'}]
    assembled=asyncio.run(window.assemble([],prior,current,[]))
    assert any('IRIS-7419' in r['content'] for r in assembled)
    assert assembled[-1]==current[0] and tokens(assembled,[])<10000
    assert len(memory.store.session_messages('agent','session',memory.clock(),limit=1000))==142


def test_long_original_tail_can_be_searched_and_paged_without_modifying_it(tmp_path):
    store=LiteStore(tmp_path)
    text='填'*25000+'🦉 IRIS-7419 精确原文结尾'
    store.append_session_message('a','s','user',text,100,1000)
    result=store.search_session('a','s','IRIS-7419','100',101)['items'][0]
    assert 'IRIS-7419' in result['content'] and result['offset']>24000
    read=store.read_session('a','s',result['id'],101,25000,4000)['items'][0]
    assert read['content']==text[25000:] and read['sha256']==result['sha256']
    assert read['totalChars']==len(text) and not read['hasMore']
    for agent,session,now in [('other','s',101),('a','other',101),('a','s',1101)]:
        assert not store.search_session(agent,session,'IRIS-7419','100',now)['items']
        assert not store.read_session(agent,session,result['id'],now)['items']


def test_summary_reference_checks_local_version_and_source_membership(tmp_path):
    memory=LiteMemory(LiteStore(tmp_path),'a','s')
    memory.append('user','银杉项目原文。')
    memory.save_local_summary({'version':0,'throughId':'1','content':'银杉项目 [m:1]'})
    assert source_ids(memory.local_summary()['content'])==['1']
    assert asyncio.run(memory.read_session('1',False,version=1))['items']
    memory.save_local_summary({'version':1,'throughId':'2','content':'新版本 [m:1]'})
    stale=asyncio.run(memory.read_session('1',False,version=1))
    assert stale['stale'] and stale['summary']['version']==2 and not stale['items']
    assert not asyncio.run(memory.read_session('999',False,version=2))['items']


class CompetingMemory:
    guard=None
    def __init__(self):
        self.winner={'version':1,'throughId':'2','content':'另一副本已保存的权威摘要 [m:1]','sourceIds':['1']}
        self.writes=[]
    async def load_summary(self,owner):
        return {'version':0,'throughId':'0','content':''}
    async def search_session(self,*args):return {'items':[]}
    async def save_summary(self,owner,update):
        self.writes.append(update)
        return {**self.winner,'saved':False}


class SummaryModel:
    async def chat(self,*args,**kwargs):return ChatResult('本副本的候选摘要 [m:999999]',())


def test_losing_replica_adopts_canonical_cursor_and_restores_uncovered_turns(monkeypatch):
    monkeypatch.setenv('KEEL_CONTEXT_TOKEN_BUDGET','10000')
    memory=CompetingMemory()
    window=ContextWindow(SummaryModel(),memory,'a','staging','s','owner',True,None,'trace')
    prior=[{'id':'1','role':'user','content':'填'*12000},{'id':'2','role':'assistant','content':'早期答复'},
           {'id':'3','role':'user','content':'后'*6000},{'id':'4','role':'assistant','content':'尚未被权威摘要覆盖'},
           {'id':'5','role':'user','content':'近期问题'},{'id':'6','role':'assistant','content':'近期回答'}]
    current=[{'role':'user','content':'问'*1600}]
    result=asyncio.run(window.assemble([],prior,current,[]))
    assert memory.writes and memory.writes[0]['throughId']=='4'
    assert window.state['version']==1 and window.state['throughId']=='2'
    assert any('权威摘要' in r['content'] for r in result)
    assert any(r['content']=='后'*6000 for r in result)
    assert all('999999' not in r['content'] for r in result)
    assert tokens(result,[])<10000


def test_empty_external_history_evaluation_and_oversized_current_do_not_search(monkeypatch):
    monkeypatch.setenv('KEEL_CONTEXT_TOKEN_BUDGET','10000')
    class Forbidden:
        def __getattr__(self,name):raise AssertionError('must not access historical storage')
    for prior,evaluation,text in [([],None,'你好'),([{'id':'1','role':'user','content':'旧'}],'eval','你好'),
                                  ([{'id':'1','role':'user','content':'旧'}],None,'填'*12000)]:
        window=ContextWindow(None,Forbidden(),'a','staging','s','owner',True,evaluation,'trace')
        result=asyncio.run(window.assemble([],prior,[{'role':'user','content':text}],[]))
        assert result[-1]['content']==text


def test_stale_reference_is_retried_once_against_latest_summary(tmp_path):
    from keel.session_history import SessionRecall
    class Memory:
        def __init__(self):self.reads=[]
        async def search_session(self,*args):return {"items":[]}
        async def read_session(self,ref,external,owner,version):
            self.reads.append((ref,version))
            if version==1:
                return {"stale":True,"summary":{"version":2,"throughId":"4","content":"新事实 [m:3]","sourceIds":["3"]},"items":[]}
            return {"items":[{"id":"3","role":"user","content":"新事实原文"}]}
    memory=Memory();recall=SessionRecall(memory,True,"owner","a","trace")
    items,state=asyncio.run(recall.collect([{"id":"5","role":"user","content":"近期"}],
        [{"role":"user","content":"回查原文"}],{"version":1,"throughId":"2","content":"旧 [m:1]"}))
    assert memory.reads==[("1",1),("3",2)] and state["version"]==2
    assert items==[{"id":"3","role":"user","content":"新事实原文"}]


def test_retrieval_failure_preserves_current_and_recent_messages(tmp_path, monkeypatch):
    monkeypatch.setenv('KEEL_CONTEXT_TOKEN_BUDGET','10000')
    class Memory:
        async def load_summary(self,*args):return {"version":0,"throughId":"0","content":""}
        async def search_session(self,*args):raise TimeoutError('unavailable')
    prior=[{"id":"1","role":"user","content":"近期完整问题"},{"id":"2","role":"assistant","content":"近期完整回答"}]
    current=[{"role":"user","content":"当前问题"}]
    window=ContextWindow(None,Memory(),'a','staging','s','owner',True,None,'trace')
    result=asyncio.run(window.assemble([],prior,current,[]))
    assert result==[{"role":r['role'],"content":r['content']} for r in prior]+current
