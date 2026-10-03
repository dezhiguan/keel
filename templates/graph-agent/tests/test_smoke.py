import json
import subprocess
import sys
from pathlib import Path

from tools.checkpoint import advance, load, save, start

ROOT = Path(__file__).resolve().parents[1]


def test_plan_then_draft_then_done():
    state = advance(start("风机"))
    assert state["step"] == "draft"
    state = advance(state)
    assert state["step"] == "done"
    assert state["notes"][-1] == "answer:风机"


def test_done_checkpoint_cannot_advance():
    state = advance(advance(start("风机")))
    try:
        advance(state)
    except ValueError as exc:
        assert "done" in str(exc)
    else:
        raise AssertionError("expected ValueError")


def test_save_and_load_round_trip():
    ref = save(advance(start("海缆")))
    assert load(ref)["topic"] == "海缆"
    assert load(ref)["step"] == "draft"


def test_ref_is_stable_across_saves():
    state = advance(start("海缆"))
    ref = save(state)
    again = save(load(ref))
    assert again == ref


def test_checkpoint_survives_a_new_process(tmp_path=None):
    code = """
import json, sys
sys.path.insert(0, sys.argv[1])
from tools.checkpoint import advance, load, save, start
if sys.argv[2] == "start":
    print(save(advance(start("风机"))))
else:
    state = advance(load(sys.argv[2]))
    print(json.dumps(state))
"""
    root = str(ROOT)
    started = subprocess.run([sys.executable, "-c", code, root, "start"], check=True, capture_output=True, text=True)
    ref = started.stdout.strip()
    resumed = subprocess.run([sys.executable, "-c", code, root, ref], check=True, capture_output=True, text=True)
    state = json.loads(resumed.stdout)
    assert state["step"] == "done"
    assert state["notes"][-1] == "answer:风机"


def test_seed_has_plan_and_draft_tags():
    rows = [json.loads(line) for line in (ROOT / "evals/seed.jsonl").read_text().splitlines() if line.strip()]
    assert {tag for row in rows for tag in row["tags"]} >= {"plan", "draft"}


def test_scorer():
    from evals.scorers import step_match
    assert step_match("outline:风机", "outline:风机", ["plan"]) == 1


def test_gitignore():
    assert ".keel/" in (ROOT / ".gitignore").read_text().splitlines()


def test_app_suspends_mid_graph():
    assert 'ctx.suspend("input_required"' in (ROOT / "app.py").read_text()


def test_platform_is_not_asked_to_store_the_state_body():
    source = (ROOT / "tools/checkpoint.py").read_text()
    assert "platform stores only the ref" in source
