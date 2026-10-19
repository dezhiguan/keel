"""V2-only prompt and skill evidence; the original 52 cases stay independently comparable."""
import json
if __package__:
    from .baseline_cases import cases as core_cases
else:
    from baseline_cases import cases as core_cases


def cases(*, skill_version=None):
    rows = core_cases()
    requirement = {'layer':'dev','kind':'CREATE','target_agent':'qa-meta-v2-reader','template':'tool-agent',
                   'title':'研发任务目录查询员','goal':'读取明确任务编号对应的任务与只读工具目录，证据完整时返回PASS；缺少编号或证据时返回BLOCKED',
                   'users':'研发人员','io':'text输入到decision及证据','success':'只读查询真实证据，不执行写操作',
                   'owner':'研发效能组 / amy','dailyBudgetCny':10,
                   'tools':['devflow.job.read','devflow.catalog.search']}
    tools=[{'name':name,'access':'read','risk':'low','approval':'none'} for name in requirement['tools']]
    work={'workflow':True,'action':'GENERATE','jobId':'DF-9200','requirement':requirement,
          'draft':{'spec':{key:requirement[key] for key in ('title','goal','users','io','success')},
                   'manifest':{'spec':{'tools':tools}}},'visibleSeeds':[]}
    rows.append({'id':'ma-v2-generation','input':{'text':json.dumps(work,ensure_ascii=False)},
                 'expected':{'kind':'generation','template':'tool-agent','tools':requirement['tools'],'skillVersion':skill_version},
                 'tags':['generation-contract']})
    sha='b'*40
    role='''import json

async def execute(data, ctx):
    text = data.get("text")
    if not isinstance(text, str) or not text.strip():
        return {"decision": "BLOCKED"}
    prompt = await ctx.prompt("role")
    reply = await ctx.llm.chat([{"role": "system", "content": prompt.compile()}, {"role": "user", "content": text}], prompt=prompt)
    try:
        value = json.loads(reply)
    except (ValueError, TypeError):
        return {"decision": "BLOCKED"}
    if not isinstance(value, dict) or value.get("decision") not in ("PASS", "BLOCKED"):
        return {"decision": "BLOCKED"}
    return {"decision": value["decision"]}
'''
    test='''import asyncio
from unittest.mock import AsyncMock, MagicMock
from types import SimpleNamespace
from role import execute

def test_normal():
    prompt=MagicMock()
    prompt.compile.return_value="static role policy"
    ctx=SimpleNamespace(prompt=AsyncMock(return_value=prompt),llm=SimpleNamespace(chat=AsyncMock(return_value='{"decision":"PASS"}')))
    assert asyncio.run(execute({"text":"普通任务"},ctx))["decision"]=="PASS"

def test_missing_text():
    assert asyncio.run(execute({},None))["decision"]=="BLOCKED"
'''
    snapshot={'role.py':role,'app.py':'from role import execute\n',
              'agent.yaml':'apiVersion: keel/v1\nkind: Agent\nmetadata:\n  name: qa-meta-v2-reader\nspec:\n  kind: workflow\n  tools: []\n',
              'prompts/role.md':'只判断用户任务是否资料齐备。仅约束单次模型响应：返回JSON对象，decision为PASS或BLOCKED。不执行外部工具或发布。',
              'tests/test_role.py':test}
    review={'workflow':True,'action':'REVIEW','jobId':'DF-9201','requirement':requirement,
            'draft':{'spec':{'goal':'判断资料齐备性，返回decision，不操作外部系统'},'manifest':{'spec':{'tools':[]}}},
            'delivery':{'sha':sha},'snapshot':{'sha':sha,'files':[{'path':p,'content':v} for p,v in snapshot.items()]}}
    rows.append({'id':'ma-v2-review','input':{'text':json.dumps(review,ensure_ascii=False)},
                 'expected':{'kind':'review','sha':sha,'checks':['modelAccess','toolAuthorization','audit','visibleCaseOverfit','releaseApproval','behaviorTests','hardcodedPrompt']},
                 'tags':['review-contract']})
    return rows


if __name__=='__main__':
    for row in cases(): print(json.dumps(row,ensure_ascii=False))
