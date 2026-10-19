"""Atomic intake checkpoints on the retained volume shared by runtime replicas."""

import json
import os
import tempfile
from pathlib import Path

ROOT = Path(".keel/meta-agent")


def folder(run_id: str) -> Path:
    if not run_id or "/" in run_id or run_id.startswith("."):
        raise ValueError("invalid run_id")
    return ROOT / run_id


def save(run_id: str, state: dict) -> None:
    path = folder(run_id)
    path.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix="state-", suffix=".tmp", dir=path)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as file:
            json.dump(state, file, ensure_ascii=False, indent=2)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, path / "state.json")
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load(run_id: str) -> dict | None:
    path = folder(run_id) / "state.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))
