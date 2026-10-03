import json
from pathlib import Path

from tools.delegation import delegate

ROOT = Path(__file__).resolve().parents[1]
DECLARED = ["research-bot", "draft-bot"]


def test_declared_child_shares_the_parent_trace():
    out = delegate(DECLARED, "research-bot", "风机", {"cny": 1.0, "steps": 4}, "trace-1")
    assert out["trace_id"] == "trace-1"
    assert out["agent"] == "research-bot"


def test_budget_decreases_for_the_child():
    out = delegate(DECLARED, "research-bot", "风机", {"cny": 1.0, "steps": 4}, "trace-1")
    assert out["budget"] == {"cny": 0.9, "steps": 3}


def test_second_delegate_keeps_one_trace_and_spends_more():
    first = delegate(DECLARED, "research-bot", "风机", {"cny": 1.0, "steps": 4}, "trace-1")
    second = delegate(DECLARED, "draft-bot", first["result"], first["budget"], first["trace_id"])
    assert second["trace_id"] == "trace-1"
    assert second["budget"] == {"cny": 0.8, "steps": 2}
    assert second["result"] == "draft:notes:风机"


def test_undeclared_delegate_is_refused():
    try:
        delegate(DECLARED, "other-bot", "x", {"cny": 1.0, "steps": 4}, "trace-1")
    except PermissionError as exc:
        assert "not declared" in str(exc)
    else:
        raise AssertionError("expected PermissionError")


def test_exhausted_steps_stop_delegation():
    try:
        delegate(DECLARED, "research-bot", "x", {"cny": 1.0, "steps": 0}, "trace-1")
    except RuntimeError as exc:
        assert "budget" in str(exc)
    else:
        raise AssertionError("expected RuntimeError")


def test_exhausted_money_stops_delegation():
    try:
        delegate(DECLARED, "draft-bot", "x", {"cny": 0.0, "steps": 2}, "trace-1")
    except RuntimeError:
        return
    raise AssertionError("expected RuntimeError")


def test_manifest_lists_both_children():
    text = (ROOT / "agent.yaml").read_text()
    assert "research-bot" in text
    assert "draft-bot" in text


def test_seed_covers_delegate_and_denied():
    rows = [json.loads(line) for line in (ROOT / "evals/seed.jsonl").read_text().splitlines() if line.strip()]
    tags = {tag for row in rows for tag in row["tags"]}
    assert {"delegate", "denied", "budget"} <= tags


def test_gitignore_covers_local_state():
    assert ".keel/" in (ROOT / ".gitignore").read_text().splitlines()


def test_app_steps_follow_both_children():
    source = (ROOT / "app.py").read_text()
    assert 'ctx.step("research-bot")' in source
    assert 'ctx.step("draft-bot")' in source
