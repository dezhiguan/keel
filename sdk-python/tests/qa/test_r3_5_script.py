import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "docs/qa/r3-5/run-first-employee.sh"
JOB = ROOT / "docs/qa/r3-5/job.json"


def test_dry_run_creates_then_only_reads_evidence():
    text = SCRIPT.read_text(encoding="utf-8")
    assert "keel.ragforge.net" not in text
    assert "sk-" not in text
    assert "/h4/" not in text
    assert "decision=APPROVE" not in text
    job = json.loads(JOB.read_text(encoding="utf-8"))
    assert job["targetAgent"] == "spec-agent"
    assert job["template"] == "graph-agent"
    assert job["execution"] is True
    assert set(job["tools"]) == {"devflow.catalog.search", "devflow.stage.report"}
    env = os.environ.copy()
    for name in ("KEEL_SERVER_URL", "KEEL_CONSOLE_COOKIE", "KEEL_JOB_ID"):
        env.pop(name, None)
    missing = subprocess.run(["bash", str(SCRIPT)], cwd=SCRIPT.parent, env=env,
                             text=True, capture_output=True)
    assert missing.returncode == 2
    dry = subprocess.run(["bash", str(SCRIPT), "--dry-run"], cwd=SCRIPT.parent, env=env,
                         text=True, capture_output=True)
    assert dry.returncode == 0, dry.stderr
    for step in ("spec-agent", "graph-agent", "不申请 H4", "execution", "本月上线", "7"):
        assert step in dry.stdout
    create = subprocess.run(["bash", str(SCRIPT), "create"], cwd=SCRIPT.parent, env=env,
                            text=True, capture_output=True)
    assert create.returncode == 2
    assert "KEEL_SERVER_URL" in create.stderr
