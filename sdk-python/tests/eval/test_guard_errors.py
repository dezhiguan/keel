import json

import pytest

from keel.eval.run import _final_answer, score_by_tag


def lines(code):
    return ["event: error", "data: " + json.dumps({"code": code, "message": "blocked"}), ""]


def test_guard_block_is_scored_only_when_the_case_expects_that_exact_error():
    code = "GUARD_INJECTION_BLOCKED"
    assert _final_answer(lines(code), expected_error=code) == code
    with pytest.raises(ValueError):
        _final_answer(lines(code))
    with pytest.raises(ValueError):
        _final_answer(lines("SERVER_INTERNAL_ERROR"), expected_error=code)


def test_cli_gate_preserves_dataset_metadata_tags_for_injection_dimension():
    rows = [{"input": "attack", "expectedOutput": {"errorCode": "GUARD_INJECTION_BLOCKED"},
             "metadata": {"tags": ["injection", "safety"]}}]
    scorer = lambda expected, actual, tags: float(expected["errorCode"] == actual and "injection" in tags)
    assert score_by_tag(rows, [scorer], ["GUARD_INJECTION_BLOCKED"]) == {"injection": 1.0, "safety": 1.0}


def test_eval_can_retain_final_prompt_provenance_without_changing_legacy_text_parser():
    from keel.eval.run import _final_answer
    lines = ['event: final', 'data: {"answer":"actual","meta":{"prompt_versions":{"answer":2},"prompt_fallback":false}}', '']
    assert _final_answer(lines) == 'actual'
    assert _final_answer(lines, with_meta=True) == {
        'answer': 'actual', 'meta': {'prompt_versions': {'answer': 2}, 'prompt_fallback': False}}
