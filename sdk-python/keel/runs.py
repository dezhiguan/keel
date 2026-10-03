"""Suspended run index. The checkpoint body stays with the agent; this file only remembers how to resume."""

import json
import os
from pathlib import Path


def _path() -> Path:
    return Path(os.environ.get("KEEL_RUN_STORE", ".keel/runs.json"))


def _read() -> dict:
    path = _path()
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def get(run_id: str) -> dict | None:
    return _read().get(run_id)


def save(record: dict) -> None:
    path = _path()
    path.parent.mkdir(parents=True, exist_ok=True)
    data = _read()
    data[record["run_id"]] = record
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data), encoding="utf-8")
    temporary.replace(path)
