"""The agent owns the checkpoint contents. The platform only stores the ref."""
import json
from pathlib import Path
from uuid import uuid4

ROOT = Path(".keel/checkpoints")


def save_checkpoint(state: dict) -> str:
    ROOT.mkdir(parents=True, exist_ok=True)
    ref = "ckpt_" + uuid4().hex
    (ROOT / ref).write_text(json.dumps(state), encoding="utf-8")
    return ref


def load_checkpoint(ref: str) -> dict:
    return json.loads((ROOT / ref).read_text(encoding="utf-8"))


def create_work_order(title: str) -> str:
    return "created:" + title
