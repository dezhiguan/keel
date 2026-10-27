"""Trusted task context, propagated without putting tickets in prompts or trace metadata."""
import os
import re
from contextvars import ContextVar
from contextlib import contextmanager
from keel.auth.client_assertion import ASSERTION_TYPE, sign_client_assertion
from keel.http_pool import new_async_client
from keel.protocol.errors import ErrorCode, KeelError

current = ContextVar('keel_task_cost', default=None)


def validate(value):
    if not isinstance(value, dict) or not re.fullmatch(r'ct_[a-f0-9]{32}', str(value.get('taskId', ''))) or not re.fullmatch(r'tc_[a-f0-9]{64}', str(value.get('ticket', ''))):
        raise KeelError(ErrorCode.COST_SCOPE_DENIED)
    if value.get('mode') not in ('observe', 'enforce') or not isinstance(value.get('policyVersion'), str):
        raise KeelError(ErrorCode.COST_SCOPE_DENIED)
    return value


@contextmanager
def bind(value):
    token = current.set(validate(value) if value is not None else None)
    try:
        yield
    finally:
        current.reset(token)


def public(value):
    return ({k: value[k] for k in ('taskId', 'policyVersion', 'mode')} | {'coverage': 'managed-v1'}) if value else {'coverage': 'not-connected'}


async def send(agent, suffix, body):
    base = os.environ.get('KEEL_SERVER_URL', '').rstrip('/')
    audience = os.environ.get('KEEL_SERVICE_AUDIENCE', '')
    key = os.environ.get('KEEL_CLIENT_PRIVATE_KEY', '')
    if not all((base, audience, key)):
        raise KeelError(ErrorCode.COST_SCOPE_DENIED, '任务成本需要完整的平台服务身份配置')
    headers = {'X-Client-Id': agent, 'X-Client-Assertion-Type': ASSERTION_TYPE,
               'X-Client-Assertion': sign_client_assertion(agent, audience, key)}
    async with new_async_client(timeout=15) as client:
        response = await client.post(f'{base}/api/v1/agents/{agent}/cost-control/tasks{suffix}', json=body, headers=headers)
    if response.status_code >= 400:
        raise KeelError(ErrorCode.COST_SCOPE_DENIED if response.status_code in (401,403) else ErrorCode.SERVER_INTERNAL_ERROR)
    return response.json().get('data') or {}


async def establish(agent, env, incoming, text, evaluation):
    if incoming is not None:
        return validate(incoming)
    # No fake coverage for local or legacy agents without a configured service identity.
    if os.environ.get('KEEL_TASK_COST_AUTOCONNECT')!='true' or not os.environ.get('KEEL_SERVER_URL') or not os.environ.get('KEEL_CLIENT_PRIVATE_KEY'):
        return None
    return validate(await send(agent, '', {'env': env, 'inputChars': len(text), 'evaluationRun': evaluation or ''}))


async def bind_trace(agent, env, value, trace):
    if value:
        await send(agent, '/'+value['taskId']+'/bind', {'env':env, 'taskId':value['taskId'], 'traceId':trace})


async def finish(agent, env, value, state, trace):
    if value:
        await send(agent, '/'+value['taskId']+'/finish', {'env':env, 'taskId':value['taskId'], 'state':state, 'traceId':trace})


def prepare(messages):
    """Remove only identical adjacent system messages; never remove tool protocol groups or user originals."""
    value = current.get()
    if not value or not value.get('features', {}).get('contextDedupe'):
        return messages
    result=[]
    for message in messages:
        if result and message.get('role')=='system' and result[-1].get('role')=='system' and message==result[-1] and type(message) is type(result[-1]):
            continue
        result.append(message)
    return result
