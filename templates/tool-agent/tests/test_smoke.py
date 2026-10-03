import asyncio
import json
import os
from pathlib import Path

import httpx

os.environ.setdefault("KEEL_ENV", "dev")
os.environ.setdefault("KEEL_AUDIT_URL", "http://127.0.0.1:9")
os.environ.setdefault("KEEL_AUDIT_TOKEN", "local-dev-token")
os.environ.setdefault("KEEL_AUDIT_SPOOL_PATH", ".keel/audit-spool.jsonl")

from app import app
from tools.checkpoint import create_work_order, load_checkpoint

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
    assert payload["ref"].startswith("ckpt_")
    assert load_checkpoint(payload["ref"])["title"] == "停电检修"


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
