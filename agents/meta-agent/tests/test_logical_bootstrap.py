import pytest
import base64
import contextlib
import io
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import httpx

from ops import bootstrap_runtime as bootstrap
from ops.bootstrap_runtime import logical_key_request, missing_key_request


def policy(**changes):
    return {'alias':'meta-agent-dev','blocked':False,'models':['qwen3.8-flash'],
            'fallback':[],'dailyBudgetCny':10,'allowFallback':False,'rpm':8,'tpm':9000,'concurrency':1,**changes}


ROUTES={'data':[{'logical':'chat-default','primary':'qwen3.8-flash','secondary':'deepseek-flash'}]}


def test_logic_authorization_preserves_existing_budget_and_limits():
    result=logical_key_request(policy(),ROUTES)
    assert result['models']==['chat-default','deepseek-flash','qwen3.8-flash']
    for field in ('dailyBudgetCny','fallback','allowFallback','rpm','tpm','concurrency'):
        assert result[field]==policy()[field]
    assert logical_key_request(policy(models=result['models']),ROUTES) is None


def test_existing_physical_targets_still_need_logical_authorization():
    result=logical_key_request(policy(models=['qwen3.8-flash','deepseek-flash']),ROUTES)
    assert 'chat-default' in result['models']


@pytest.mark.parametrize('change',[{'blocked':True},{'alias':'another-agent-dev'}])
def test_bootstrap_does_not_unblock_or_change_another_agent(change):
    with pytest.raises(ValueError): logical_key_request(policy(**change),ROUTES)


POLICY = Path(bootstrap.__file__).with_name('runtime_key_policy.json')


def test_missing_key_uses_explicit_policy_and_live_targets():
    recovery = json.loads(POLICY.read_text())
    request = missing_key_request(recovery, ROUTES)
    assert request['models'] == ['chat-default', 'deepseek-flash', 'qwen3.8-flash']
    assert request['dailyBudgetCny'] == 10
    assert request['fallback'] == []
    assert request['allowFallback'] is False
    with pytest.raises(ValueError):
        missing_key_request({**recovery, 'alias': 'other-agent-prod'}, ROUTES)
    with pytest.raises(ValueError):
        missing_key_request(recovery, {'data': []})


@pytest.mark.parametrize('current,apply', [(None, False), (None, True), (policy(), True),
    (policy(models=['chat-default', 'deepseek-flash', 'qwen3.8-flash']), True)])
def test_bootstrap_recovery_and_repeat_preserve_runtime(monkeypatch, current, apply):
    calls = []
    def kube(namespace, *args, body=None):
        calls.append((namespace, args, body))
        if args[:2] == ('get', 'secret'):
            values = {'admin-key': 'admin-fixture'} if args[2] == 'keel-llm' else {'KEEL_CLIENT_PRIVATE_KEY': 'identity-fixture'}
            return json.dumps({'data': {k: base64.b64encode(v.encode()).decode() for k,v in values.items()}}).encode()
        if args[0] == 'exec':
            payload = json.loads(body)
            if payload['path'].endswith('/logical-routes'):
                result = ROUTES
            elif payload['method'] == 'POST':
                result = {'key': 'issued-fixture'}
            else:
                assert payload['allowMissing'] is True
                result = current
            return json.dumps(result).encode()
        assert args[:3] == ('patch', 'secret', 'keel-meta-agent')
        return b'patched'
    monkeypatch.setattr(bootstrap, 'kube', kube)
    monkeypatch.setattr(sys, 'argv', ['bootstrap', '--missing-key-policy', str(POLICY)] + (['--apply'] if apply else []))
    bootstrap.main()
    writes = [json.loads(body) for _,args,body in calls if args[0] == 'exec' and json.loads(body)['method'] == 'POST']
    patches = [json.loads(body)['stringData'] for _,args,body in calls if args[0] == 'patch']
    rotate = apply and (current is None or 'chat-default' not in current['models'])
    assert len(writes) == int(rotate)
    assert len(patches) == int(apply)
    if rotate:
        assert patches[0]['KEEL_LLM_KEY'] == 'issued-fixture'
        expected = json.loads(POLICY.read_text()) if current is None else current
        for field in ('dailyBudgetCny', 'fallback', 'allowFallback', 'rpm', 'tpm', 'concurrency'):
            assert writes[0]['body'][field] == expected[field]
    elif apply:
        assert 'KEEL_LLM_KEY' not in patches[0]


@pytest.mark.parametrize('status,code,missing', [(404, 'SERVER_NOT_FOUND', True),
    (404, 'OTHER', True), (401, 'AUTH', True), (403, 'AUTH', True),
    (503, 'UNAVAILABLE', True), (404, 'SERVER_NOT_FOUND', False)])
def test_remote_only_recovers_explicit_missing_key(monkeypatch, status, code, missing):
    transport = httpx.MockTransport(lambda request: httpx.Response(status, json={'code': code}))
    client = httpx.Client(base_url='http://gateway', transport=transport)
    monkeypatch.setitem(sys.modules, 'httpx', SimpleNamespace(Client=lambda **kwargs: client))
    monkeypatch.setattr(sys, 'stdin', io.StringIO(json.dumps({'admin': 'fixture', 'method': 'GET',
        'path': '/admin/v1/keys/meta-agent-dev', 'allowMissing': missing})))
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        if status == 404 and code == 'SERVER_NOT_FOUND' and missing:
            exec(bootstrap.REMOTE, {})
            assert json.loads(output.getvalue()) is None
        else:
            with pytest.raises(httpx.HTTPStatusError):
                exec(bootstrap.REMOTE, {})
