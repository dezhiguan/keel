from types import SimpleNamespace
import pytest
from keel.eval.worker import summarize
from keel.eval.run import load_scorers


def test_structured_skill_input_survives_langfuse_json_decoding():
    import json
    from keel.eval.worker import case_text
    raw = '{"workflow":true,"action":"GENERATE","requirement":"生成项目"}'
    assert case_text(raw) == raw
    assert json.loads(case_text(json.loads(raw))) == json.loads(raw)


@pytest.mark.parametrize("text", ["普通需求", "", '{"workflow":true}'])
def test_explicit_text_envelopes_preserve_input_including_empty_cases(text):
    from keel.eval.worker import case_text
    assert case_text({"text": text}) == text


def item(value, tag="accuracy"):
    return SimpleNamespace(item=SimpleNamespace(id="case-1"), trace_id="trace-1",
                           evaluations=[SimpleNamespace(name=tag, value=value)])


def result(items, baseline=None):
    return summarize(items, agent="qa-agent", dataset="qa/smoke", run_id="ev_test", env="dev",
                     gate={"minScore": .85, "maxRegression": 2}, baseline=baseline)


def test_real_scores_fail_and_pass_gates_without_fabricating_a_baseline():
    failed = result([item(1), item(0)])
    assert failed["caseCount"] == 2
    assert failed["scoreTotal"] == .5
    assert failed["passed"] is False
    assert len(failed["newFailures"]) == 1
    assert failed["dimensions"][0]["prodScore"] is None
    assert result([item(1), item(1)])["passed"] is True


def test_tag_regression_blocks_an_otherwise_passing_run():
    measured = result([item(.9)], baseline={"accuracy": 1})
    assert measured["scoreTotal"] == .9
    assert measured["passed"] is False
    assert measured["dimensions"][0]["verdict"] == "EXCEEDED"


@pytest.mark.parametrize("rows", [[], [item(float("nan"))], [item(2)],
                                  [SimpleNamespace(evaluations=[], item=None, trace_id=None)]])
def test_empty_missing_or_invalid_scores_are_execution_errors(rows):
    with pytest.raises(ValueError):
        result(rows)


def test_missing_project_scorer_is_not_replaced_by_an_automatic_perfect_score(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_scorers(tmp_path / "scorers.py")


def test_calls_overlap_and_preserve_the_current_trace_context(monkeypatch):
    import asyncio
    from contextvars import ContextVar
    from threading import Barrier
    from keel.eval import worker
    current = ContextVar("case_trace")
    barrier = Barrier(2, timeout=2)
    def invoke(endpoint, text, **options):
        barrier.wait()
        return text, current.get()
    monkeypatch.setattr(worker, "invoke_text", invoke)
    async def run():
        current.set("parent-trace")
        return await asyncio.gather(worker.invoke_case("http://agent", "a"),
                                    worker.invoke_case("http://agent", "b"))
    assert asyncio.run(run()) == [("a", "parent-trace"), ("b", "parent-trace")]


@pytest.mark.parametrize("actual", [["a"], ["a", "a"], ["a", "b", "c"], ["a", "x"]])
def test_missing_duplicate_or_unexpected_cases_cannot_produce_a_passing_gate(actual):
    from keel.eval.worker import require_complete, IncompleteExperiment
    rows = [SimpleNamespace(item=SimpleNamespace(id=id)) for id in actual]
    with pytest.raises(IncompleteExperiment):
        require_complete({"a", "b"}, rows)


def test_complete_case_identity_set_accepts_any_execution_order():
    from keel.eval.worker import require_complete
    rows = [SimpleNamespace(item=SimpleNamespace(id=id)) for id in ["b", "a"]]
    require_complete({"a", "b"}, rows)


@pytest.mark.parametrize("metadata", [
    [{"prompt_versions": {"answer": 1}}, {"prompt_versions": {"answer": 2}}],
    [{"prompt_versions": {"answer": 1}, "prompt_fallback": True}],
    [{"prompt_versions": {"answer": True}}],
])
def test_prompt_version_conflicts_or_fallback_cannot_be_release_evidence(metadata):
    from keel.eval.worker import prompt_evidence
    rows = [SimpleNamespace(output={"answer": "actual answer", "meta": meta}) for meta in metadata]
    with pytest.raises(ValueError):
        prompt_evidence(rows)


def test_actual_prompt_versions_are_collected_without_requiring_a_model_for_blocked_cases():
    from keel.eval.worker import prompt_evidence
    rows = [SimpleNamespace(output={"answer": "actual", "meta": {"prompt_versions": {"answer": 2}}}),
            SimpleNamespace(output={"answer": "GUARD_INJECTION_BLOCKED", "meta": {}})]
    assert prompt_evidence(rows) == {"answer": 2}
