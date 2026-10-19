"""Suspended run index. The checkpoint body stays with the agent; this file only remembers how to resume."""

import json
import os
import fcntl
from contextlib import contextmanager
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


@contextmanager
def _locked():
    path = _path()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_suffix(path.suffix + ".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            yield path
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def _write(path: Path, data: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data), encoding="utf-8")
    temporary.replace(path)


def save(record: dict) -> None:
    with _locked() as path:
        data = _read()
        data[record["run_id"]] = record
        _write(path, data)


def claim(run_id: str, resume_token: str) -> bool:
    """Consume a suspended run once across processes sharing the run store."""
    with _locked() as path:
        data = _read()
        record = data.get(run_id)
        if record is None or record["status"] != "SUSPENDED" or record["resume_token"] != resume_token:
            return False
        record["status"] = "DONE"
        _write(path, data)
        return True
