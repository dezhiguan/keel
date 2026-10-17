import importlib.util
from pathlib import Path

import pytest


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


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
