"""Agent-owned checkpoint. The platform stores only the ref string.

A LangGraph checkpointer persists the same kind of state and returns a ref
the agent passes to ctx.suspend. Restoring is the agent's job.
"""
import json
from pathlib import Path
from uuid import uuid4

ROOT = Path(".keel/checkpoints")
STEPS = ("plan", "draft", "done")


def save(state: dict) -> str:
    ROOT.mkdir(parents=True, exist_ok=True)
    ref = state.get("ref") or ("ckpt_" + uuid4().hex)
    state = dict(state)
    state["ref"] = ref
    (ROOT / ref).write_text(json.dumps(state), encoding="utf-8")
    return ref


def load(ref: str) -> dict:
    return json.loads((ROOT / ref).read_text(encoding="utf-8"))


def start(topic: str) -> dict:
    return {"topic": topic, "step": "plan", "notes": []}


def advance(state: dict) -> dict:
    state = dict(state)
    step = state["step"]
    if step == "plan":
        state["notes"].append("outline:" + state["topic"])
        state["step"] = "draft"
    elif step == "draft":
        state["notes"].append("answer:" + state["topic"])
        state["step"] = "done"
    else:
        raise ValueError("checkpoint is already done")
    return state
