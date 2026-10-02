"""Synchronous local audit writes and chain verification."""

import json
from dataclasses import dataclass
from typing import Collection

from keel._generated.audit import AuditEvent

from .chain import chain_hash
from .store import LiteStore


@dataclass(frozen=True)
class VerifyResult:
    ok: bool
    count: int
    broken_event_id: str | None = None
    reason: str | None = None


class LiteAudit:
    def __init__(self, store: LiteStore, capture_fields: Collection[str] = ()):
        self.store = store
        self.capture_fields = frozenset(capture_fields)

    def record(self, *, agent: str, action: str, risk: str, decision: str,
               resource: str | None = None, payload: dict | None = None,
               run_id: str | None = None, trace_id: str | None = None,
               approver: str | None = None, input_digest: str | None = None,
               event_id: str | None = None, ts: str | None = None) -> AuditEvent:
        return self.store.append_event(self.prepare(
            agent=agent, action=action, risk=risk, decision=decision,
            resource=resource, payload=payload, run_id=run_id, trace_id=trace_id,
            approver=approver, input_digest=input_digest, event_id=event_id, ts=ts,
        ))

    def prepare(self, *, agent: str, action: str, risk: str, decision: str,
                resource: str | None = None, payload: dict | None = None,
                run_id: str | None = None, trace_id: str | None = None,
                approver: str | None = None, input_digest: str | None = None,
                event_id: str | None = None, ts: str | None = None) -> dict:
        if not agent or (event_id is not None and not event_id):
            raise ValueError("agent and event_id must be nonempty")
        if risk == "high" and decision == "approved" and not approver:
            raise ValueError("approved high-risk action requires approver")
        if payload is not None and not isinstance(payload, dict):
            raise ValueError("payload must be an object")
        data = {
            "agent": agent, "action": action, "risk": risk, "decision": decision,
            "resource": resource, "payload": {
                key: value for key, value in (payload or {}).items() if key in self.capture_fields
            }, "run_id": run_id, "trace_id": trace_id,
            "approver": approver, "input_digest": input_digest,
        }
        if event_id is not None:
            data["event_id"] = event_id
        if ts is not None:
            data["ts"] = ts
        return data

    def verify(self, agent: str) -> VerifyResult:
        rows = self.store.events(agent)
        previous = ""
        for index, row in enumerate(rows):
            event_id = row["event_id"]
            try:
                snapshot = json.loads(row["event_json"])
                event = AuditEvent.model_validate(snapshot)
                payload = json.loads(row["payload_json"])
                if payload != (snapshot.get("payload") or {}):
                    return VerifyResult(False, index, event_id, "payload mismatch")
                if row["prev_hash"] != previous or event.prev_hash != previous:
                    return VerifyResult(False, index, event_id, "previous hash mismatch")
                if row["hash"] != event.hash or chain_hash(event) != event.hash:
                    return VerifyResult(False, index, event_id, "hash mismatch")
                for column, field in (("event_id", "event_id"), ("ts", "ts"),
                                      ("agent", "agent"), ("env", "env"), ("action", "action"),
                                      ("risk", "risk"), ("decision", "decision"),
                                      ("resource", "resource"), ("input_digest", "input_digest")):
                    if row[column] != snapshot.get(field):
                        return VerifyResult(False, index, event_id, f"{column} mismatch")
                previous = event.hash
            except (ValueError, TypeError, KeyError) as exc:
                return VerifyResult(False, index, event_id, str(exc))
        head = self.store.chain_head(agent)
        if (head is None and rows) or (head is not None and
                                      (not rows or head["count"] != len(rows) or
                                       head["last_hash"] != previous or
                                       head["last_event_id"] != rows[-1]["event_id"])):
            return VerifyResult(False, len(rows), rows[-1]["event_id"] if rows else None,
                                "chain head mismatch")
        return VerifyResult(True, len(rows))
