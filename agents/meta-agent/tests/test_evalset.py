from model_context import model_context
import asyncio
import json
import pytest

from evalset import expand, parse


HUMAN = [{"caseId": "h-1", "input": {"text": "normal"}, "expected": {"status": "READY"}, "tags": ["normal"]}]
AGENT = {"cases": [{"caseId": "a-1", "input": {"text": "missing"},
                    "expected": {"status": "BLOCKED"}, "tags": ["boundary"], "reason": "空输入场景"}]}


def test_expansion_keeps_human_examples_out_of_model_output():
    prompts = []

    class Llm:
        async def chat(self, messages, **kwargs):
            prompts.append(messages[0]["content"])
            return json.dumps(AGENT, ensure_ascii=False)

    result = asyncio.run(expand({"requirement": {"target_agent": "spec-agent"}, "humanSeeds": HUMAN},
                                model_context(Llm())))
    assert result == AGENT
    assert "h-1" in prompts[0]
    assert result["cases"][0]["caseId"] != HUMAN[0]["caseId"]


def test_expansion_rejects_reused_human_case_and_input():
    copied = {"cases": [{**AGENT["cases"][0], "input": HUMAN[0]["input"]}]}
    _, errors = parse(json.dumps(copied), HUMAN)
    assert any("重复" in error for error in errors)
    _, errors = parse(json.dumps({"cases": [{**AGENT["cases"][0], "caseId": "h-1"}]}), HUMAN)
    assert errors


def test_expansion_uses_confirmed_spec_and_reports_invalid_json_position():
    prompts = []
    class Llm:
        async def chat(self, messages, **kwargs):
            prompts.append(messages[0]["content"])
            return '{"cases": [{"input": "\\x00"}]}' if len(prompts) == 1 else json.dumps(AGENT)
    work = {"requirement": {"target_agent": "guide"}, "humanSeeds": HUMAN,
            "draft": {"spec": {"io": "仅一个任务编号，中文紧邻编号合法"}}}
    assert asyncio.run(expand(work, model_context(Llm()))) == AGENT
    assert "中文紧邻编号合法" in prompts[0]
    assert "Invalid" in prompts[1] and "行第" in prompts[1]


@pytest.mark.parametrize('field,invalid', [
    ('caseId', 'b-1'), ('caseId', 'f-1'), ('caseId', 'a-中文编号'),
    ('input', None), ('input', 'plain input'),
    ('expected', ['PASS']), ('tags', []), ('reason', '   '),
])
def test_expansion_repairs_invalid_field_without_relaxing_schema(field, invalid):
    broken = {**AGENT['cases'][0], field: invalid}
    replies = [json.dumps({'cases': [broken]}, ensure_ascii=False), json.dumps(AGENT)]
    prompts = []

    class Llm:
        async def chat(self, messages, **kwargs):
            prompts.append(messages[0]['content'])
            return replies.pop(0)

    assert asyncio.run(expand({'requirement': {'target_agent': 'spec-agent'}, 'humanSeeds': HUMAN},
                              model_context(Llm()))) == AGENT
    assert len(prompts) == 2
    assert field in prompts[1].removeprefix(prompts[0])
