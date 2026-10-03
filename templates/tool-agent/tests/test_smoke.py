import asyncio
import json
import os
import socket
import threading
from pathlib import Path

import httpx
import uvicorn

from keel.lite import LiteServer, LiteStore

os.environ.setdefault("KEEL_ENV", "dev")
os.environ.setdefault("KEEL_AUDIT_TOKEN", "local-dev-token")
os.environ.setdefault("KEEL_AUDIT_SPOOL_PATH", ".keel/audit-spool.jsonl")

def _free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

_port = _free_port()
os.environ["KEEL_AUDIT_URL"] = f"http://127.0.0.1:{_port}"
_lite = uvicorn.Server(uvicorn.Config(LiteServer(LiteStore("."), ("title",)),
                                      host="127.0.0.1", port=_port, log_level="error"))
threading.Thread(target=_lite.run, daemon=True).start()
for _ in range(100):
    if _lite.started:
        break
    threading.Event().wait(0.05)

from app import app
from tools.checkpoint import create_work_order

ROOT = Path(__file__).resolve().parents[1]


def test_high_risk_tool_requires_approval():
    text = (ROOT / "agent.yaml").read_text()
    assert "risk: high" in text
    assert "approval: required" in text


def test_gitignore_covers_checkpoints():
    assert ".keel/" in (ROOT / ".gitignore").read_text().splitlines()


def test_seed_covers_read_and_approval():
    rows = [json.loads(line) for line in (ROOT / "evals/seed.jsonl").read_text().splitlines() if line.strip()]
    tags = {tag for row in rows for tag in row["tags"]}
    assert {"read", "approval"} <= tags
    assert 3 <= len(rows) <= 5


def test_scorer_is_exact():
    from evals.scorers import exact
    assert exact("ping", "ping", ["read"]) == 1.0
    assert exact("ping", "pong", ["read"]) == 0.0


def test_work_order_result_has_no_secret_material():
    assert create_work_order("风扇") == "created:风扇"


async def _invoke(text):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post("/v1/invoke", json={"input": {"text": text}})


def test_high_risk_call_suspends_for_approval():
    response = asyncio.run(_invoke("更换风扇"))
    assert "event: suspend" in response.text
    assert '"reason":"approval"' in response.text.replace(" ", "")


def _payload(response):
    data_line = next(line for line in response.text.splitlines() if line.startswith("data: "))
    return json.loads(data_line.removeprefix("data: "))


def test_suspend_event_points_at_a_checkpoint():
    response = asyncio.run(_invoke("停电检修"))
    payload = _payload(response)
    assert payload["reason"] == "approval"
    assert payload["ref"].startswith("ap_")
    saved = json.loads(Path(f".keel/checkpoints/ckpt_{payload['run_id']}").read_text())
    assert saved["kwargs"]["title"] == "停电检修"


def test_approve_then_resume_finishes_the_tool():
    response = asyncio.run(_invoke("更换风扇"))
    payload = _payload(response)
    approved = httpx.post(
        f"{os.environ['KEEL_AUDIT_URL']}/api/v1/approvals/{payload['ref']}/decision",
        json={"decision": "APPROVE"}, timeout=5)
    assert approved.status_code == 200

    async def resume():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.post(
                f"/v1/runs/{payload['run_id']}/resume",
                json={"resume_token": payload["resume_token"], "decision": "approve"})

    resumed = asyncio.run(resume())
    assert "created:更换风扇" in resumed.text


def test_checkpoint_round_trip():
    from tools.checkpoint import save_checkpoint, load_checkpoint as load
    ref = save_checkpoint({"tool": "create_work_order", "title": "风扇"})
    assert load(ref)["title"] == "风扇"


def test_resume_runs_the_tool_only_with_the_saved_title():
    from tools.checkpoint import save_checkpoint, load_checkpoint as load
    ref = save_checkpoint({"tool": "create_work_order", "title": "风扇"})
    state = load(ref)
    assert create_work_order(state["title"]) == "created:风扇"


def test_health_names_the_agent():
    async def call():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.get("/v1/health")
    assert asyncio.run(call()).json()["status"] == "ok"
