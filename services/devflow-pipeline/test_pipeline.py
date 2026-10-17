import importlib.util
from pathlib import Path
import shutil
import sys
import time

import pytest


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


@pytest.fixture
def pipeline_directory():
    directory = Path("/tmp") / f"keel-pipeline-{time.time_ns()}"
    directory.mkdir()
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def test_successful_ci_response_can_retain_a_historical_task_error(monkeypatch):
    runner = module("runner")
    state = {"phase": "STAGING_READY", "error": "Historical generation failure", "pipeline": {"status": "RUNNING"}}
    monkeypatch.setattr(runner, "credentials", lambda: {})
    monkeypatch.setattr(runner, "inside", lambda *args: {"ok": True, "data": state})
    assert runner.api("DF-0012", "staging", "status") == state


def test_actual_ci_api_rejection_still_stops_the_pipeline(monkeypatch):
    runner = module("runner")
    monkeypatch.setattr(runner, "credentials", lambda: {})
    monkeypatch.setattr(runner, "inside", lambda *args: {"ok": False, "error": "CI identity rejected"})
    with pytest.raises(RuntimeError, match="CI identity rejected"):
        runner.api("DF-0012", "staging", "context")


def test_export_saves_context_before_a_status_report_can_fail(monkeypatch, pipeline_directory):
    import json
    runner = module("runner")
    context = {"jobId": "DF-0012", "env": "staging", "sha": "a" * 40}
    monkeypatch.setattr(runner, "api", lambda *args: context)
    monkeypatch.setattr(sys, "argv", ["runner.py", "export", "DF-0012", "staging", str(pipeline_directory),
                                      "https://github.com/dezhiguan/keel/actions/runs/123"])
    def report(*args):
        assert json.loads((pipeline_directory / "context.json").read_text()) == context
        raise RuntimeError("Status unavailable")
    monkeypatch.setattr(runner, "status", report)
    with pytest.raises(RuntimeError, match="Status unavailable"):
        runner.main()


def test_failure_without_a_saved_context_revalidates_and_reports_only_failure(monkeypatch, pipeline_directory):
    runner = module("runner")
    context = {"jobId": "DF-0012", "env": "staging", "sha": "a" * 40}
    calls = []
    def api(job, env, action):
        assert (job, env, action) == ("DF-0012", "staging", "context")
        return context
    monkeypatch.setattr(runner, "api", api)
    monkeypatch.setattr(runner, "status", lambda *args: calls.append(args))
    monkeypatch.setattr(sys, "argv", ["runner.py", "fail", "DF-0012", "staging", str(pipeline_directory),
                                      "https://github.com/dezhiguan/keel/actions/runs/123"])
    runner.main()
    assert len(calls) == 1
    assert calls[0][:3] == (context, "https://github.com/dezhiguan/keel/actions/runs/123", "FAILED")
    assert calls[0][-1] is False


def test_snapshot_rejects_escape_before_writing(tmp_path):
    runner = module("runner")
    with pytest.raises(ValueError):
        runner.write_snapshot(tmp_path / "project", [{"path": "../outside", "content": "forbidden"}])
    assert not (tmp_path / "outside").exists()


def test_runtime_scorer_rejects_misleading_and_malformed_output():
    gate = module("gate")
    expected = {"decision": "PASS"}
    assert gate.score(expected, '{"decision":"BLOCKED","reason":"PASS mentioned"}') == 0
    assert gate.score(expected, 'Example {"decision":"PASS"}') == 0
    assert gate.score(expected, '{"decision":"PASS"}') == 1


def test_prod_preview_uses_candidate_prompt_without_changing_formal_manifest():
    runner = module("runner")
    context = {"candidatePromptLabel": "candidate-" + "a" * 40,
               "manifest": {"spec": {"prompts": {"source": "langfuse"}}}}
    assert runner.runtime_manifest(context, True)["spec"]["prompts"]["label"] == context["candidatePromptLabel"]
    assert "label" not in context["manifest"]["spec"]["prompts"]
    assert runner.runtime_manifest(context) == context["manifest"]


def test_failed_visible_evaluation_never_fetches_hidden_or_submits_pass(monkeypatch):
    gate = module("gate")
    monkeypatch.setattr(gate, "evaluate", lambda *args: [{"tag": "normal", "score": 0.0, "count": 1}])
    monkeypatch.setattr(gate, "request", lambda *args: pytest.fail("Must stop before hidden or gate submission"))
    config = {"credentials": {}, "context": {"jobId": "DF-1", "env": "staging", "visibleSeeds": [],
              "manifest": {"spec": {"runtime": {"endpoint": "http://unused"}}}}}
    assert gate.run(config)["passed"] is False


def test_only_visible_evaluation_exports_bounded_failure_reasons(monkeypatch):
    gate = module("gate")
    monkeypatch.setattr(gate, "invoke_text", lambda *args, **kwargs: '{"decision":"BLOCKED","block_reason":"missing evidence"}')
    cases = [{"caseId": "public-1", "input": {"text": "audit"}, "expected": {"decision": "PASS"}, "tags": ["normal"]}]
    failures = []
    assert gate.evaluate(cases, "http://unused", "staging", "run", failures)[0]["score"] == 0
    assert failures == [{"caseId": "public-1", "expected": {"decision": "PASS"},
                         "actualDecision": "BLOCKED", "reason": "missing evidence"}]
    gate.evaluate(cases, "http://unused", "staging", "run")
    assert len(failures) == 1
