"""The optimized budget estimator must preserve the original hard gate exactly."""
import json
import random
from keel.llm.client import estimate_context_tokens

def test_c_count_matches_original_estimate_for_unicode_tools_and_long_history():
    rng=random.Random(20261026)
    for size in (0,1,127,7000,26000):
        text=''.join(rng.choice('ABC012\\\n\"中中文界😀é𐐀') for _ in range(size))
        messages=[{'role':'system','content':'规则'},{'role':'user','content':text},
                  {'role':'assistant','tool_calls':[{'id':'x','function':{'name':'tool.read','arguments':'{}'}}]}]
        tools=[{'type':'function','function':{'name':'tool.read','description':'读取','parameters':{'type':'object'}}}]
        body=json.dumps({'messages':messages,'tools':tools},ensure_ascii=False,default=str)
        ascii_count=sum(ord(ch)<128 for ch in body)
        expected=(ascii_count+3)//4+len(body)-ascii_count+4*len(messages)
        assert estimate_context_tokens(messages,tools)==expected
