import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "docs/qa/r3-1/run-staging-release.sh"


def test_dry_run_lists_the_release_steps_without_a_default_host():
    text = SCRIPT.read_text(encoding="utf-8")
    assert "keel.ragforge.net" not in text
    assert "sk-" not in text
    env = os.environ.copy()
    for name in ("KEEL_SERVER_URL", "KEEL_AGENT_ENDPOINT", "KEEL_RELEASE_IMAGE"):
        env.pop(name, None)
    missing = subprocess.run(["bash", str(SCRIPT)], cwd=SCRIPT.parent, env=env,
                             text=True, capture_output=True)
    assert missing.returncode == 2
    assert "KEEL_SERVER_URL" in missing.stderr
    dry = subprocess.run(["bash", str(SCRIPT), "--dry-run"], cwd=SCRIPT.parent, env=env,
                         text=True, capture_output=True)
    assert dry.returncode == 0, dry.stderr
    for step in ("keel register --env staging", "seed-fail.jsonl", "seed.jsonl",
                 "keel release --env staging", "keel release --env prod"):
        assert step in dry.stdout
    assert "必须失败" in dry.stdout
