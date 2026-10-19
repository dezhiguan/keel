import asyncio
import copy
import json

import httpx

from keel.context import Context
from keel.llm.client import LlmClient
from keel.llm.tool_names import VALID_NAME, wire_tools
from keel.manifest import AgentManifest


NAME = 'devflow.catalog.search'
TOOLS = [{'type': 'function', 'function': {'name': NAME, 'parameters': {'type': 'object'}}}]


def test_wire_aliases_are_valid_collision_safe_and_leave_caller_data_unchanged():
    messages = [{'role': 'assistant', 'tool_calls': [{'id': 'c1', 'type': 'function',
                 'function': {'name': NAME, 'arguments': '{}'}}]}]
    original = copy.deepcopy(messages)
    encoded_messages, definitions, decoded = wire_tools(messages, TOOLS)
    alias = definitions[0]['function']['name']
    assert alias != NAME and VALID_NAME.fullmatch(alias)
    assert decoded[alias] == NAME
    assert encoded_messages[0]['tool_calls'][0]['function']['name'] == alias
    assert messages == original and TOOLS[0]['function']['name'] == NAME
    collision = {'type': 'function', 'function': {'name': alias}}
    _, definitions, decoded = wire_tools(messages, TOOLS + [collision])
    assert definitions[0]['function']['name'] != alias
    assert definitions[1]['function']['name'] == alias
    assert decoded[alias] == alias
    assert decoded[definitions[0]['function']['name']] == NAME


def test_namespaced_tool_round_trip_preserves_grants_history_and_final_choice(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv('KEEL_LLM_BASE_URL', 'https://gateway.test/v1')
    monkeypatch.setenv('KEEL_LLM_KEY', 'test-key')
    requests = []
    seen = []

    def response(content='', calls=()):
        return {'id': '1', 'object': 'chat.completion', 'created': 1, 'model': 'test',
                'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': content, 'tool_calls': list(calls)},
                             'finish_reason': 'tool_calls' if calls else 'stop'}]}

    def handle(request):
        body = json.loads(request.content)
        requests.append(body)
        alias = body['tools'][0]['function']['name']
        assert VALID_NAME.fullmatch(alias) and alias != NAME
        if len(requests) == 1:
            return httpx.Response(200, json=response(calls=[{'id': 'c1', 'type': 'function',
                                  'function': {'name': alias, 'arguments': '{"query":"qa"}'}}]))
        assert body['tool_choice'] == 'none'
        assert body['messages'][-2]['tool_calls'][0]['function']['name'] == alias
        assert json.loads(body['messages'][-1]['content']) == {'query': 'qa'}
        return httpx.Response(200, json=response('已核查实际结果'))

    class Audit:
        async def record(self, *args, **kwargs):
            pass

        async def aclose(self):
            pass

    manifest = AgentManifest.model_validate({'apiVersion': 'keel/v1', 'metadata': {'name': 'qa'}, 'spec': {
        'runtime': {'endpoint': 'http://test'}, 'models': {'default': 'test'},
        'tools': [{'name': NAME, 'risk': 'low'}]}})
    llm = LlmClient('qa', 'test', 'trace-1', http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
    ctx = Context('qa', 'run-1', 'trace-1', asyncio.Queue(), manifest=manifest, llm_client=llm,
                  tool_functions={NAME: lambda query: seen.append(query) or {'query': query}}, audit_reporter=Audit())

    async def scenario():
        try:
            return await ctx.loop([{'role': 'user', 'content': 'query'}], TOOLS, max_tool_rounds=1)
        finally:
            await ctx.aclose()

    assert asyncio.run(scenario()).answer == '已核查实际结果'
    assert seen == ['qa']
    events = [ctx._events.get_nowait() for _ in range(ctx._events.qsize())]
    assert any(getattr(e, 'name', None) == NAME for e in events)


def test_streamed_fragmented_wire_name_is_restored(monkeypatch):
    monkeypatch.setenv('KEEL_LLM_BASE_URL', 'https://gateway.test/v1')
    monkeypatch.setenv('KEEL_LLM_KEY', 'test-key')

    def handle(request):
        alias = json.loads(request.content)['tools'][0]['function']['name']
        chunks = []
        for name, arguments in [(alias[:8], '{"query":'), (alias[8:], '"qa"}')]:
            data = {'id': '1', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'test',
                    'choices': [{'index': 0, 'delta': {'tool_calls': [{'index': 0, 'id': 'c1', 'type': 'function',
                                 'function': {'name': name, 'arguments': arguments}}]}, 'finish_reason': None}]}
            chunks.append('data: ' + json.dumps(data) + '\n\n')
        return httpx.Response(200, headers={'content-type': 'text/event-stream'},
                              content=(''.join(chunks) + 'data: [DONE]\n\n').encode())

    client = LlmClient('qa', 'test', 'trace-1', on_token=lambda text: None,
                       http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))

    async def scenario():
        try:
            return await client.chat([{'role': 'user', 'content': 'query'}], tools=TOOLS)
        finally:
            await client.aclose()

    result = asyncio.run(scenario())
    assert result.tool_calls[0].name == NAME
    assert result.tool_calls[0].arguments == {'query': 'qa'}
