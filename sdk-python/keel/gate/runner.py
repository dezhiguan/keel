"""Local gate: invoke each seed row, score by tag, then report."""

import json
import uuid
from pathlib import Path

import yaml

from keel.eval.dataset import load_seed
from keel.eval.run import invoke_text, load_scorers, score_by_tag
from keel.gate.compare import judge
from keel.gate.report import record
from keel.manifest import load_manifest


def run_gate(*, env: str, seed_file: str, scorers_file: str, endpoint: str | None, baseline_file: str | None,
             root: str | Path = ".", holdout_job: str | None = None) -> str:
    root = Path(root)
    manifest_path = root / "agent.yaml"
    manifest = load_manifest(manifest_path)
    raw = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
    gate = (((raw.get("spec") or {}).get("eval") or {}).get("gate") or {})
    min_score = float(gate.get("minScore", 0.85))
    max_regression = float(gate.get("maxRegression", 0.02))
    target = endpoint or manifest.spec.runtime.endpoint
    rows = load_seed(root / seed_file)
    scorers = load_scorers(root / scorers_file)
    gate_run_id = uuid.uuid4().hex
    answers = []
    for row in rows:
        text = row.get("input")
        if isinstance(text, dict):
            text = text.get("text", "")
        answers.append(invoke_text(target, str(text), eval_run_id=gate_run_id, env=env))
    by_tag = score_by_tag(rows, scorers, answers)
    baseline = None
    if baseline_file:
        baseline = {str(key): float(value) for key, value in json.loads((root / baseline_file).read_text(encoding="utf-8")).items()}
    passed, reasons = judge(by_tag, baseline, min_score=min_score, max_regression=max_regression)
    summary = {"gateRunId": gate_run_id, "passed": passed, "byTag": by_tag, "reasons": reasons}
    print(json.dumps(summary, ensure_ascii=False))
    if not passed:
        raise SystemExit(1)
    record(manifest.metadata.name, gate_run_id, True)
    marker = root / ".keel"
    marker.mkdir(exist_ok=True)
    (marker / "gate-run-id").write_text(gate_run_id, encoding="utf-8")
    if holdout_job:
        from keel.holdout_run import run_holdout
        run_holdout(job_id=holdout_job, scorers=scorers, endpoint=target, env=env)
    return gate_run_id
