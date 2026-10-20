from types import SimpleNamespace
import pytest
from keel.eval.worker import summarize
from keel.eval.run import load_scorers


def test_concurrency_defaults_to_existing_four_slots(monkeypatch):
    from keel.eval.worker import evaluation_concurrency
    monkeypatch.delenv("KEEL_EVAL_CONCURRENCY", raising=False)
    assert evaluation_concurrency() == 4


@pytest.mark.parametrize("value", ["1", "2", "3", "4"])
def test_concurrency_can_be_reduced_for_a_low_budget_agent(monkeypatch, value):
    from keel.eval.worker import evaluation_concurrency
    monkeypatch.setenv("KEEL_EVAL_CONCURRENCY", value)
    assert evaluation_concurrency() == int(value)


@pytest.mark.parametrize("value", ["0", "5", "-1", "1.5", "", "many"])
def test_invalid_concurrency_is_rejected(monkeypatch, value):
    from keel.eval.worker import evaluation_concurrency
    monkeypatch.setenv("KEEL_EVAL_CONCURRENCY", value)
    with pytest.raises(ValueError):
        evaluation_concurrency()


def test_worker_passes_agent_concurrency_to_langfuse_without_skipping_cases(monkeypatch, tmp_path):
    from keel.eval import worker
    monkeypatch.chdir(tmp_path)
    (tmp_path / "agent.yaml").write_text("metadata: {name: qa-agent}\nspec: {}\n")
    for key, value in {"KEEL_EVAL_CONCURRENCY": "1", "KEEL_EVAL_ENV": "staging",
                       "KEEL_EVAL_DATASET": "qa/smoke", "KEEL_EVAL_RUN_ID": "ev_test",
                       "KEEL_EVAL_ENDPOINT": "http://fixture"}.items():
        monkeypatch.setenv(key, value)
    measured = item(1)
    measured.output = {"answer": "unit fixture", "meta": {"prompt_versions": {"answer": 1}}}

    class Dataset:
        items = [SimpleNamespace(id="case-1", status="ACTIVE")]

        def run_experiment(self, **kwargs):
            assert kwargs["max_concurrency"] == 1
            return SimpleNamespace(item_results=[measured], dataset_run_url="http://fixture/run")

    client = SimpleNamespace(get_dataset=lambda name: Dataset(), flush=lambda: None, shutdown=lambda: None)
    monkeypatch.setattr("langfuse.Langfuse", lambda **kwargs: client)
    monkeypatch.setattr(worker, "load_scorers", lambda path: [])
    result = worker.run_experiment()
    assert result["complete"] is True and result["caseCount"] == 1
    assert result["promptVersions"] == {"answer": 1}


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
