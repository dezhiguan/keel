import asyncio
import pytest
from keel.context_window import ContextWindow, tokens
from keel.lite.store import LiteStore
from keel.lite.memory import LiteMemory
from keel.llm.client import ChatResult


class Summarizer:
    def __init__(self, fail=False):
        self.calls=[]
        self.fail=fail

    async def chat(self, messages, **options):
        if messages[0].get('content', '').startswith('你是会话事实提取器'):
            return ChatResult('{"operations":[]}', ())
        self.calls.append((messages, options))
        assert tokens(messages, []) < 10000
        assert options['emit_tokens'] is False and options['tools']==[]
        if self.fail:
            raise RuntimeError('upstream unavailable')
        return ChatResult('用户曾要求冒泡排序；重复填充文字不含其他事实。', ())


def setup(monkeypatch, tmp_path, fail=False, evaluation=None):
    monkeypatch.setenv('KEEL_CONTEXT_TOKEN_BUDGET','10000')
    store=LiteStore(tmp_path)
    memory=LiteMemory(store,'agent','session')
    for role,text in [('user','帮我写冒泡排序。'+'测'*12000),('assistant','冒泡排序代码'),
                      ('user','最近问题，保留全文'),('assistant','最近完整回复')]:
        memory.append(role,text)
    llm=Summarizer(fail)
    window=ContextWindow(llm,memory,'agent','staging','session',None,False,evaluation,'trace')
    return memory,llm,window


def test_long_history_recovers_and_originals_remain_complete(monkeypatch,tmp_path):
    memory,llm,window=setup(monkeypatch,tmp_path)
    before=memory.history()
    system=[{'role':'system','content':'系统规则'}]
    current=[{'role':'user','content':'你是谁'}]
    result=asyncio.run(window.assemble(system,memory.context_history(),current,[]))
    assert tokens(result,[])<10000
    assert result[0]==system[0] and result[-1]==current[0]
    assert {'role':'user','content':'最近问题，保留全文'} in result
    assert {'role':'assistant','content':'最近完整回复'} in result
    assert any('冒泡排序' in x['content'] for x in result)
    assert memory.history()==before and len(before[0]['content'])>12000
    assert memory.local_summary()['throughId']=='2'
    second=ContextWindow(llm,memory,'agent','staging','session',None,False,None,'trace2')
    count=len(llm.calls)
    again=asyncio.run(second.assemble(system,memory.context_history(),current,[]))
    assert len(llm.calls)==count and tokens(again,[])<10000


def test_summary_failure_does_not_advance_cursor_or_poison_next_turn(monkeypatch,tmp_path):
    memory,llm,window=setup(monkeypatch,tmp_path,fail=True)
    current=[{'role':'user','content':'继续聊天'}]
    result=asyncio.run(window.assemble([],memory.context_history(),current,[]))
    assert result[-1]==current[0] and tokens(result,[])<10000
    assert memory.local_summary()['throughId']=='0'
    assert len(memory.history()[0]['content'])>12000


def test_long_current_input_is_not_silently_truncated(monkeypatch,tmp_path):
    memory,llm,window=setup(monkeypatch,tmp_path)
    current=[{'role':'user','content':'测'*12000}]
    result=asyncio.run(window.assemble([],memory.context_history(),current,[]))
    assert result==current and tokens(result,[])>10000
    assert not llm.calls


def test_eval_compression_does_not_persist_summary(monkeypatch,tmp_path):
    memory,llm,window=setup(monkeypatch,tmp_path,evaluation='evaluation')
    result=asyncio.run(window.assemble([],memory.context_history(),[{'role':'user','content':'继续'}],[]))
    assert tokens(result,[])<10000 and memory.local_summary()['version']==0


def test_local_summary_cas_and_scope(monkeypatch,tmp_path):
    memory,llm,window=setup(monkeypatch,tmp_path)
    one={'version':0,'throughId':'2','content':'first'}
    memory.save_local_summary(one)
    memory.save_local_summary({**one,'content':'stale'})
    assert memory.local_summary()['content']=='first'
    other=LiteMemory(memory.store,'other-agent','session')
    assert other.local_summary()['version']==0
    other=LiteMemory(memory.store,'agent','other-session')
    assert other.local_summary()['version']==0


def test_current_tool_chain_is_preserved(monkeypatch,tmp_path):
    memory,llm,window=setup(monkeypatch,tmp_path)
    chain=[{'role':'user','content':'查询'}, {'role':'assistant','content':'','tool_calls':[{'id':'c','type':'function','function':{'name':'find','arguments':'{}'}}]},
           {'role':'tool','tool_call_id':'c','content':'真实结果'}]
    result=asyncio.run(window.assemble([],memory.context_history(),chain,[]))
    assert result[-3:]==chain
