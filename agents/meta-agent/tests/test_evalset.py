import asyncio
import json

from evalset import expand, parse


HUMAN = [{"caseId": "h-1", "input": {"text": "normal"}, "expected": {"status": "READY"}, "tags": ["normal"]}]
AGENT = {"cases": [{"caseId": "a-1", "input": {"text": "missing"},
                    "expected": {"status": "BLOCKED"}, "tags": ["boundary"], "reason": "空输入场景"}]}


def test_expansion_keeps_human_examples_out_of_model_output():
    prompts = []

    class Llm:
        async def chat(self, messages):
            prompts.append(messages[0]["content"])
            return json.dumps(AGENT, ensure_ascii=False)

    result = asyncio.run(expand({"requirement": {"target_agent": "spec-agent"}, "humanSeeds": HUMAN},
                                type("Ctx", (), {"llm": Llm()})()))
    assert result == AGENT
    assert "h-1" in prompts[0]
    assert result["cases"][0]["caseId"] != HUMAN[0]["caseId"]


def test_expansion_rejects_reused_human_case_and_input():
    copied = {"cases": [{**AGENT["cases"][0], "input": HUMAN[0]["input"]}]}
    _, errors = parse(json.dumps(copied), HUMAN)
    assert any("重复" in error for error in errors)
    _, errors = parse(json.dumps({"cases": [{**AGENT["cases"][0], "caseId": "h-1"}]}), HUMAN)
    assert errors
