"""Run state kept by the agent itself; Keel only stores the run_id as the checkpoint reference.

TODO(DF-5d): move to the devflow job ledger so state survives pod restarts in K8s.
"""

import json
from pathlib import Path

ROOT = Path(".keel/meta-agent")


def folder(run_id: str) -> Path:
    if not run_id or "/" in run_id or run_id.startswith("."):
        raise ValueError("invalid run_id")
    return ROOT / run_id


def save(run_id: str, state: dict) -> None:
    path = folder(run_id)
    path.mkdir(parents=True, exist_ok=True)
    (path / "state.json").write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def load(run_id: str) -> dict | None:
    path = folder(run_id) / "state.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))
