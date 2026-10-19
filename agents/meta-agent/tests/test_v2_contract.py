import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
import asyncio
import pytest
from evals.v2_cases import cases
from evals.scorers import baseline
from workflow import execute
from keel.protocol.errors import KeelError

ROOT=Path(__file__).resolve().parents[1]


def test_v2_keeps_core_dimensions_and_adds_prompt_coverage():
    rows=cases()
    assert len(rows)==54
    assert len({row['id'] for row in rows})==54
    core=[row for row in rows if not row['id'].startswith('ma-v2-')]
    assert len(core)==52 and len({tag for row in core for tag in row['tags']})==16
    generated=next(row for row in cases(skill_version=1) if row['id']=='ma-v2-generation')
    assert generated['expected']['skillVersion']==1


def test_generation_preview_rejects_non_evaluation_requests():
    work=json.loads(next(row for row in cases() if row['id']=='ma-v2-generation')['input']['text'])
    with pytest.raises(KeelError,match='只供隔离评测'):
        asyncio.run(execute(work,SimpleNamespace(eval_run_id=None)))


def test_skill_artifact_and_string_scoring_contract():
    body=json.loads((ROOT/'skills/keel-project-development.json').read_text())
    assert body['instructions']==(ROOT/'skills/keel-project-development.md').read_text()
    assert body['tools']==[] and body['version']==1
    assert len(body['cases'])==1
    expected=json.dumps({'errorCode':'SERVER_INVALID_PARAM'})
    assert baseline(expected,'SERVER_INVALID_PARAM',[])==1
    assert baseline(expected,'PASS',[])==0


def test_evaluation_preview_uses_skill_and_logical_gateway_without_delivery(gateway, monkeypatch):
    import httpx
    from app import app
    from test_candidate import ROLE_CODE, TEST_CODE
    from test_meta_agent import terminal
    from test_workflow import work

    request = work()
    request['action'] = 'GENERATE'
    request['requirement'].update(target_agent='qa-meta-preview', template='graph-agent',tools=[])
    request['draft'] = {'spec':{'goal':'把需求变成结构化结论'},'manifest':{'spec':{'tools':[]}}}
    candidate = {'files':[{'path':'role.py','content':ROLE_CODE},
                          {'path':'prompts/role.md','content':'岗位任务：{{input}}'},
                          {'path':'tests/test_role.py','content':TEST_CODE}]}
    gateway.replies.append(json.dumps(candidate))
    def no_delivery(*args, **kwargs):
        raise AssertionError('evaluation preview must never deliver a repository')
    monkeypatch.setattr('workflow.deliver', no_delivery)

    async def call():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
            return await client.post('/v1/invoke',json={'input':{'text':json.dumps(request)}},
                                     headers={'X-Keel-Eval-Run':'test-v2-preview'})
    kind,event = terminal(asyncio.run(call()))
    assert kind=='final', event
    output=json.loads(event['answer'])
    assert output['candidate']==candidate and output['skillVersion']==1
    assert gateway.requests[0]['model']=='chat-default'
    assert any('人工审核的项目开发技能' in message['content'] for message in gateway.requests[0]['messages'])
