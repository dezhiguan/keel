import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from keel.skills import Skill
from keel.protocol.errors import ErrorCode, KeelError
from model_steps import PROJECT_SKILL, project_guidance


def context(declared=True):
    return SimpleNamespace(
        manifest=SimpleNamespace(spec=SimpleNamespace(skills=[SimpleNamespace(name=PROJECT_SKILL,version=1)] if declared else [])),
        skills=SimpleNamespace(load=AsyncMock(return_value=Skill(PROJECT_SKILL,1,'规范正文',[],['示例']))))


def test_pinned_project_skill_is_loaded_and_used():
    ctx=context()
    result=asyncio.run(project_guidance(ctx))
    assert '规范正文' in result and '示例' in result
    assert ctx.project_skill_version==1
    ctx.skills.load.assert_awaited_once_with(PROJECT_SKILL)


def test_undeclared_skill_is_not_loaded():
    ctx=context(False)
    assert asyncio.run(project_guidance(ctx))==''
    ctx.skills.load.assert_not_called()


def test_missing_grant_fails_explicitly():
    ctx=context()
    ctx.skills.load.side_effect=KeelError(ErrorCode.SKILL_NOT_GRANTED)
    with pytest.raises(KeelError) as error:
        asyncio.run(project_guidance(ctx))
    assert error.value.code==ErrorCode.SKILL_NOT_GRANTED
