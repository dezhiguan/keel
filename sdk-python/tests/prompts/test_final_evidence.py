import asyncio
from types import SimpleNamespace

from keel.context import Context


def test_final_records_actual_loaded_versions_and_overrides_caller_forged_metadata():
    context = Context('agent', 'run', 'trace', asyncio.Queue())
    async def get(agent, name, trace):
        return SimpleNamespace(version=2, fallback=False)
    context._prompts = SimpleNamespace(get=get)
    asyncio.run(context.prompt('answer'))
    event = context.final('actual', meta={'prompt_versions': {'answer': 99}, 'other': True})
    assert event.meta == {'prompt_versions': {'answer': 2}, 'prompt_fallback': False, 'other': True}


def test_fallback_cannot_be_disguised_by_caller_metadata():
    context = Context('agent', 'run', 'trace', asyncio.Queue())
    async def get(agent, name, trace):
        return SimpleNamespace(version=None, fallback=True)
    context._prompts = SimpleNamespace(get=get)
    asyncio.run(context.prompt('answer'))
    assert context.final('actual', meta={'prompt_fallback': False}).meta['prompt_fallback'] is True
