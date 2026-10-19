from types import SimpleNamespace
import pytest
from keel.eval.worker import summarize
from keel.eval.run import load_scorers


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
