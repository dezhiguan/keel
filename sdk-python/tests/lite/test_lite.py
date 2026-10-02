import asyncio
import json
import re
import sqlite3
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from keel._generated.audit import AuditEvent
from keel.lite import LiteApproval, LiteAudit, LiteServer, LiteStore
from keel.lite.chain import _normalize, canonical_json, chain_hash

SUITE = Path(__file__).resolve().parents[3] / "contracts" / "tests"


@pytest.mark.parametrize("case", [case for case in __import__("yaml").safe_load(
    (SUITE / "cases.yaml").read_text()) if "canonical_sha256" in case],
    ids=lambda case: case["file"])
def test_canonical_matches_cross_language_contract(case):
    data = json.loads((SUITE / "fixtures" / case["file"]).read_text())
    assert chain_hash(AuditEvent.model_validate(data)) == case["canonical_sha256"]


def test_audit_chain_detects_payload_tampering(tmp_path):
    store = LiteStore(tmp_path)
    audit = LiteAudit(store, capture_fields={"allowed"})
    first = audit.record(agent="demo", action="invoke", risk="low", decision="allowed",
                         payload={"allowed": "风机", "secret": "discard"})
    second = audit.record(agent="demo", action="tool.call", risk="high", decision="approved",
                          approver="amy", payload={"allowed": 2})
    assert store.path == tmp_path / ".keel" / "dev.db"
    assert second.prev_hash == first.hash
    assert audit.verify("demo").ok
    assert json.loads(store.events("demo")[0]["payload_json"]) == {"allowed": "风机"}
    with sqlite3.connect(store.path) as connection:
        connection.execute("UPDATE audit_event SET payload_json=? WHERE event_id=?",
                           ('{"allowed":"tampered"}', first.event_id))
    broken = audit.verify("demo")
    assert not broken.ok
    assert broken.broken_event_id == first.event_id
    assert broken.reason == "payload mismatch"


def test_approval_survives_real_process_restart(tmp_path):
    child = '''
import json, sys
from keel.lite import LiteStore, LiteAudit, LiteApproval
store = LiteStore(sys.argv[1])
event = LiteApproval(store, LiteAudit(store)).suspend(
    run_id="r1", checkpoint_ref="checkpoint://agent/r1", subject_ref="work_order_create",
    agent="demo", prompt="创建工单?", trace_id="trace-1")
print(json.dumps({"id": event.ref, "token": event.resume_token}))
'''
    done = subprocess.run([sys.executable, "-c", child, str(tmp_path)],
                          cwd=Path(__file__).resolve().parents[2], capture_output=True,
                          text=True, check=True)
    suspended = json.loads(done.stdout)
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    pending = approval.list(status="PENDING")
    assert pending["total"] == 1
    assert pending["items"][0]["runId"] == "r1"
    assert store.approval(suspended["id"])["checkpoint_ref"] == "checkpoint://agent/r1"
    approved = approval.decide(suspended["id"], "APPROVE", "amy")
    assert approved["status"] == "APPROVED"
    seen = []
    assert approval.resume(suspended["id"], suspended["token"],
                           lambda checkpoint: seen.append(checkpoint) or "continued") == "continued"
    assert seen == ["checkpoint://agent/r1"]
    assert [row["action"] for row in store.events("demo")] == [
        "approval", "run.suspend", "approval", "run.resume", "tool.call"]
    assert LiteAudit(store).verify("demo").ok
    with pytest.raises(ValueError, match="not resumable"):
        approval.resume(suspended["id"], suspended["token"], lambda _: None)


def test_failed_high_risk_audit_blocks_business_callback(tmp_path, monkeypatch):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    event = approval.suspend(run_id="r1", checkpoint_ref="cp1", subject_ref="danger-tool",
                             agent="demo", prompt="run?", trace_id="trace-1")
    approval.decide(event.ref, "APPROVE", "amy")
    called = []
    def failed_write(_):
        raise sqlite3.OperationalError("disk full")
    monkeypatch.setattr(store, "append_events", failed_write)
    with pytest.raises(sqlite3.OperationalError, match="disk full"):
        approval.resume(event.ref, event.resume_token, lambda checkpoint: called.append(checkpoint))
    assert called == []
    assert store.approval(event.ref)["resumed_at"] is None


def test_failed_pending_audit_cannot_leave_approval(tmp_path, monkeypatch):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    def failed_write(_connection, _event):
        raise sqlite3.OperationalError("disk full")
    monkeypatch.setattr(store, "_append_event", failed_write)
    with pytest.raises(sqlite3.OperationalError, match="disk full"):
        approval.create(subject_type="tool.call", subject_ref="danger-tool",
                        agent="demo", summary="run?")
    assert approval.list()["total"] == 0
    assert store.events("demo") == []


def test_failed_decision_audit_keeps_approval_pending(tmp_path, monkeypatch):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    created = approval.create(subject_type="tool.call", subject_ref="danger-tool",
                              agent="demo", summary="run?")
    before = len(store.events("demo"))
    def failed_write(_connection, _event):
        raise sqlite3.OperationalError("disk full")
    monkeypatch.setattr(store, "_append_event", failed_write)
    with pytest.raises(sqlite3.OperationalError, match="disk full"):
        approval.decide(created["id"], "APPROVE", "amy")
    assert store.approval(created["id"])["status"] == "PENDING"
    assert len(store.events("demo")) == before


def test_second_resume_audit_failure_rolls_back_first(tmp_path, monkeypatch):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    event = approval.suspend(run_id="r1", checkpoint_ref="cp1", subject_ref="danger-tool",
                             agent="demo", prompt="run?", trace_id="trace-1")
    approval.decide(event.ref, "APPROVE", "amy")
    before = len(store.events("demo"))
    original = store._append_event
    calls = 0
    def fail_second(connection, data):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise sqlite3.OperationalError("disk full")
        return original(connection, data)
    monkeypatch.setattr(store, "_append_event", fail_second)
    called = []
    with pytest.raises(sqlite3.OperationalError, match="disk full"):
        approval.resume(event.ref, event.resume_token, lambda checkpoint: called.append(checkpoint))
    assert called == []
    assert len(store.events("demo")) == before
    assert LiteAudit(store).verify("demo").ok


def test_twenty_concurrent_audit_writes_have_one_ordered_chain(tmp_path):
    stores = [LiteStore(tmp_path), LiteStore(tmp_path)]
    with sqlite3.connect(stores[0].path) as connection:
        assert connection.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
    def append(index):
        LiteAudit(stores[index % 2]).record(agent="demo", action="invoke", risk="low",
                                           decision="allowed", resource=str(index))
    with ThreadPoolExecutor(max_workers=10) as pool:
        list(pool.map(append, range(20)))
    rows = stores[0].events("demo")
    assert len(rows) == 20
    assert len({row["prev_hash"] for row in rows}) == 20
    assert stores[0].chain_head("demo")["count"] == 20
    assert LiteAudit(stores[0]).verify("demo").ok


def request(app, method, path, data=None):
    body = json.dumps(data or {}).encode()
    responses = []
    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}
    async def send(message):
        responses.append(message)
    async def run():
        await app({"type": "http", "method": method, "path": path.split("?")[0],
                   "query_string": path.partition("?")[2].encode(), "headers": []}, receive, send)
    asyncio.run(run())
    return responses[0]["status"], json.loads(responses[1]["body"])


def test_local_http_api_uses_console_approval_envelope(tmp_path):
    app = LiteServer(LiteStore(tmp_path), capture_fields={"allowed"})
    status, created = request(app, "POST", "/api/v1/approvals", {
        "subjectType": "tool.call", "subjectRef": "work_order_create", "agent": "demo",
        "runId": "r1", "checkpointRef": "cp1", "summary": "创建工单?", "risk": "HIGH",
    })
    assert status == 200 and created["code"] == "OK"
    approval = created["data"]
    assert approval["subjectType"] == "tool.call"
    assert approval["subjectRef"] == "work_order_create"
    assert approval["runId"] == "r1"
    status, listing = request(app, "GET", "/api/v1/approvals?status=PENDING&page=1&size=10")
    assert status == 200
    assert {"code", "traceId", "data"} <= listing.keys()
    assert listing["data"] == {"page": 1, "size": 10, "total": 1, "items": [approval]}
    status, decided = request(app, "POST", f"/api/v1/approvals/{approval['id']}/decision",
                              {"decision": "APPROVE"})
    assert status == 200 and decided["data"]["status"] == "APPROVED"
    status, recorded = request(app, "POST", "/api/v1/audit/events", {
        "agent": "demo", "action": "tool.call", "risk": "high", "decision": "approved",
        "approver": "local", "payload": {"allowed": 1, "secret": 2},
    })
    assert status == 200 and recorded["data"]["payload"] == {"allowed": 1}
    status, invalid = request(app, "POST", "/api/v1/audit/events", {"agent": "demo"})
    assert status == 400 and invalid["code"] == "SERVER_INVALID_PARAM"


def test_rejected_and_expired_approvals_never_resume(tmp_path):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    event = approval.suspend(run_id="r1", checkpoint_ref="cp1", subject_ref="danger-tool",
                             agent="demo", prompt="run?", trace_id="trace-1")
    rejected = approval.decide(event.ref, "REJECT", "amy")
    assert rejected["status"] == "REJECTED"
    with pytest.raises(ValueError, match="not resumable"):
        approval.resume(event.ref, event.resume_token, lambda _: None)
    expired = approval.create(subject_type="data.export", subject_ref="export-1",
                              agent="demo", summary="export?", expires_at="2000-01-01T00:00:00Z")
    assert "tool" not in expired
    count = len(store.events("demo"))
    with pytest.raises(ValueError, match="expired"):
        approval.decide(expired["id"], "APPROVE", "amy")
    assert store.approval(expired["id"])["status"] == "EXPIRED"
    assert len(store.events("demo")) == count


@pytest.mark.parametrize("change, message", [
    ({"subject_type": "unknown"}, "unknown subject type"),
    ({"risk": "critical"}, "unknown risk"),
    ({"subject_ref": ""}, "are required"),
    ({"agent": ""}, "are required"),
    ({"summary": ""}, "are required"),
    ({"checkpoint_ref": "cp1"}, "requires run_id"),
])
def test_invalid_approval_request_has_no_side_effect(tmp_path, change, message):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    request_data = {"subject_type": "tool.call", "subject_ref": "tool-a",
                    "agent": "demo", "summary": "run?"} | change
    with pytest.raises(ValueError, match=message):
        approval.create(**request_data)
    assert approval.list()["total"] == 0
    assert store.events("demo") == []


@pytest.mark.parametrize("missing", ["run_id", "checkpoint_ref", "trace_id"])
def test_suspend_needs_recoverable_run_fields(tmp_path, missing):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    fields = {"run_id": "r1", "checkpoint_ref": "cp1", "subject_ref": "tool-a",
              "agent": "demo", "prompt": "run?", "trace_id": "trace-1"}
    fields[missing] = ""
    with pytest.raises(ValueError, match="required"):
        approval.suspend(**fields)
    assert approval.list()["total"] == 0


def test_invalid_decisions_and_resume_tokens(tmp_path):
    store = LiteStore(tmp_path)
    approval = LiteApproval(store, LiteAudit(store))
    with pytest.raises(ValueError, match="decision must"):
        approval.decide("missing", "YES")
    with pytest.raises(KeyError):
        approval.decide("missing", "APPROVE")
    with pytest.raises(KeyError):
        approval.resume("missing", "x", lambda _: None)
    event = approval.suspend(run_id="r1", checkpoint_ref="cp1", subject_ref="tool-a",
                             agent="demo", prompt="run?", trace_id="trace-1")
    approval.decide(event.ref, "APPROVE")
    with pytest.raises(ValueError, match="no longer pending"):
        approval.decide(event.ref, "APPROVE")
    with pytest.raises(ValueError, match="invalid resume token"):
        approval.resume(event.ref, "wrong", lambda _: None)
    plain = approval.create(subject_type="data.export", subject_ref="export-1",
                            agent="demo", summary="export?")
    approval.decide(plain["id"], "APPROVE")
    with pytest.raises(ValueError, match="no suspended run"):
        approval.resume(plain["id"], "", lambda _: None)
    no_checkpoint = approval.create(subject_type="tool.call", subject_ref="tool-a",
                                    agent="demo", summary="run?", run_id="r2")
    approval.decide(no_checkpoint["id"], "APPROVE")
    with pytest.raises(ValueError, match="no suspended run"):
        approval.resume(no_checkpoint["id"], "", lambda _: None)


def test_canonical_null_list_float_and_zero_fraction():
    assert _normalize({"a": None, "b": [1, None]}) == {"b": [1, None]}
    assert _normalize("2026-09-29T12:49:30.000000Z", ("ts",)) == "2026-09-29T12:49:30Z"
    data = json.loads((SUITE / "fixtures" / "audit-event/valid/basic.json").read_text())
    data["payload"] = {"items": [1, 2]}
    assert '"items":[1,2]' in canonical_json(AuditEvent.model_validate(data))
    data["payload"] = {"amount": 1.5}
    with pytest.raises(ValueError, match="floating value"):
        chain_hash(AuditEvent.model_validate(data))


def test_audit_table_has_no_update_or_delete_path():
    source = "\n".join(path.read_text() for path in
                       (Path(__file__).resolve().parents[2] / "keel" / "lite").glob("*.py"))
    assert not re.search(r"\b(?:UPDATE\s+audit_event|DELETE\s+FROM\s+audit_event)\b", source, re.I)
