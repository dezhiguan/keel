import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "docs/qa/r3-2/run-approval-resume.sh"


def test_dry_run_waits_ten_minutes_and_resumes_with_consent():
    text = SCRIPT.read_text(encoding="utf-8")
    assert "keel.ragforge.net" not in text
    assert "BEGIN PRIVATE" not in text
    assert "660" in text
    assert "600" in text
    env = os.environ.copy()
    for name in ("KEEL_SERVER_URL", "KEEL_AGENT_ENDPOINT", "KEEL_CONSENT_ID"):
        env.pop(name, None)
    missing = subprocess.run(["bash", str(SCRIPT)], cwd=SCRIPT.parent, env=env,
                             text=True, capture_output=True)
    assert missing.returncode == 2
    assert "KEEL_SERVER_URL" in missing.stderr
    dry = subprocess.run(["bash", str(SCRIPT), "--dry-run"], cwd=SCRIPT.parent, env=env,
                         text=True, capture_output=True)
    assert dry.returncode == 0, dry.stderr
    assert "v1/invoke" in dry.stdout
    assert "decision=APPROVE" in dry.stdout
    assert "consent_id" in dry.stdout
    assert "sleep" in dry.stdout
