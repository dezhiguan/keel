"""Resume responses, checkpoints and gateway headers must identify the actual span."""
import asyncio
import json
import httpx
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from keel import Agent
from keel.protocol.errors import ErrorCode, KeelError
from keel.runs import get


@pytest.mark.parametrize('terminal', ['final', 'suspend', 'error'])
def test_resume_uses_real_trace_for_protocol_checkpoint_and_model_client(tmp_path, monkeypatch, terminal):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv('KEEL_RUN_STORE', str(tmp_path/'runs.json'))
    monkeypatch.setenv('KEEL_MODE', 'standalone')
    monkeypatch.setenv('KEEL_ENV', 'test')
    monkeypatch.setenv('KEEL_LLM_BASE_URL', 'http://127.0.0.1:1/v1')
    provider=TracerProvider()
    monkeypatch.setattr('keel.asgi.configure_from_env', lambda:provider)

    class ModelClient:
        def __init__(self, agent, model, trace_id, *args, **kwargs):
            self.trace_id=trace_id
        async def aclose(self): pass
    monkeypatch.setattr('keel.context.LlmClient', ModelClient)
    path=tmp_path/'agent.yaml'
    path.write_text('apiVersion: keel/v1\nmetadata:\n  name: resume-trace-agent\nspec:\n  runtime:\n    endpoint: http://localhost:8000\n  models:\n    default: chat-default\n')
    agent=Agent.from_manifest(path)
    seen=[]
    identities=[]

    @agent.entry
    async def handle(request, ctx):
        actual=f'{trace.get_current_span().get_span_context().trace_id:032x}'
        seen.append((actual,ctx.trace_id,ctx.llm.trace_id))
        identities.append(ctx.user_id)
        if not getattr(request,'resume',False) or terminal=='suspend':
            return ctx.suspend('input_required',ref=ctx.run_id,prompt='需要反馈')
        if terminal=='error': raise KeelError(ErrorCode.SERVER_INVALID_PARAM)
        return ctx.final('完成')

    app=agent.asgi()
    async def post(path, body):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
            response=await client.post(path,json=body)
        assert response.status_code==200
        events=[json.loads(line[6:]) for line in response.text.splitlines() if line.startswith('data: ')]
        return events[-1]
    first=asyncio.run(post('/v1/invoke',{'input':{'text':'开始'}, 'context':{'user_id':'54'}}))
    resumed=asyncio.run(post('/v1/runs/'+first['run_id']+'/resume',{'resume_token':first['resume_token'],'input':{'text':'继续'}, 'context':{'user_id':'other-user'}}))
    assert identities == ['54', '54']
    assert all(actual==protocol==model for actual,protocol,model in seen)
    assert resumed['trace_id']==seen[-1][0]
    assert seen[0][0]!=seen[-1][0]
    if terminal=='suspend':assert get(first['run_id'])['trace_id']==seen[-1][0]
    provider.shutdown()
