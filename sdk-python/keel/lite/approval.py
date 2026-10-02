"""Local approval lifecycle; the agent still owns its checkpoint contents."""

from collections.abc import Callable
from uuid import uuid4

from keel._generated.events import SuspendEvent

from .audit import LiteAudit
from .store import LiteStore

SUBJECT_TYPES = frozenset({"tool.call", "tool.config", "agent.config", "agent.retire", "data.export"})


def public_approval(row: dict) -> dict:
    """Match console-api.openapi.yaml's Approval fields and enum spelling."""
    result = {
        "id": row["id"], "subjectType": row["subject_type"],
        "subjectRef": row["subject_ref"], "runId": row["run_id"],
        "agent": row["agent_name"], "tool": row["subject_ref"] if row["subject_type"].startswith("tool.") else None,
        "risk": row["risk"].upper(), "traceId": row["trace_id"],
        "actorUser": row["actor_user"], "summary": row["summary"],
        "payloadDigest": row["payload_digest"], "status": row["status"],
        "createdAt": row["created_at"], "expiresAt": row["expires_at"],
        "decidedBy": row["decided_by"], "decidedAt": row["decided_at"],
    }
    for key in ("tool", "traceId", "actorUser", "payloadDigest"):
        if result[key] is None:
            result.pop(key)
    return result


class LiteApproval:
    def __init__(self, store: LiteStore, audit: LiteAudit):
        self.store = store
        self.audit = audit

    def create(self, *, subject_type: str, subject_ref: str, agent: str,
               summary: str, risk: str = "high", run_id: str | None = None,
               checkpoint_ref: str | None = None, trace_id: str | None = None,
               actor_user: str | None = None, payload_digest: str | None = None,
               expires_at: str | None = None, resume_token: str | None = None,
               _extra_audit_events: tuple[dict, ...] = ()) -> dict:
        if subject_type not in SUBJECT_TYPES:
            raise ValueError("unknown subject type")
        if risk not in {"low", "mid", "high"}:
            raise ValueError("unknown risk")
        if not subject_ref or not agent or not summary:
            raise ValueError("subject_ref, agent and summary are required")
        if checkpoint_ref and not run_id:
            raise ValueError("checkpoint_ref requires run_id")
        values = {
            "id": "ap_" + uuid4().hex,
            "subject_type": subject_type, "subject_ref": subject_ref,
            "agent_name": agent, "run_id": run_id, "checkpoint_ref": checkpoint_ref,
            "resume_token": resume_token, "summary": summary, "trace_id": trace_id,
            "actor_user": actor_user, "risk": risk, "payload_digest": payload_digest,
            "expires_at": expires_at,
        }
        pending_event = self.audit.prepare(
            agent=agent, action="approval", risk=risk, decision="pending",
            resource=subject_ref, run_id=run_id, trace_id=trace_id,
        )
        row = self.store.insert_approval(values, [pending_event, *_extra_audit_events])
        return public_approval(row)

    def suspend(self, *, run_id: str, checkpoint_ref: str, subject_ref: str,
                agent: str, prompt: str, trace_id: str,
                deadline: str | None = None, actor_user: str | None = None) -> SuspendEvent:
        if not run_id or not checkpoint_ref or not trace_id:
            raise ValueError("run_id, checkpoint_ref and trace_id are required")
        token = "rt_" + uuid4().hex
        suspended_event = self.audit.prepare(
            agent=agent, action="run.suspend", risk="high", decision="pending",
            resource=subject_ref, run_id=run_id, trace_id=trace_id,
        )
        request = self.create(
            subject_type="tool.call", subject_ref=subject_ref, agent=agent,
            summary=prompt, risk="high", run_id=run_id,
            checkpoint_ref=checkpoint_ref, trace_id=trace_id,
            actor_user=actor_user, expires_at=deadline, resume_token=token,
            _extra_audit_events=(suspended_event,),
        )
        return SuspendEvent.model_validate({
            "run_id": run_id, "reason": "approval", "ref": request["id"],
            "prompt": prompt, "deadline": deadline, "resume_token": token,
            "trace_id": trace_id,
        })

    def decide(self, approval_id: str, decision: str, decided_by: str = "local") -> dict:
        if decision not in {"APPROVE", "REJECT"}:
            raise ValueError("decision must be APPROVE or REJECT")
        row = self.store.approval(approval_id)
        if row is None:
            raise KeyError(approval_id)
        if row["status"] != "PENDING":
            raise ValueError("approval is no longer pending")
        status = "APPROVED" if decision == "APPROVE" else "REJECTED"
        audit_event = self.audit.prepare(
            agent=row["agent_name"], action="approval", risk=row["risk"],
            decision="approved" if decision == "APPROVE" else "rejected",
            resource=row["subject_ref"], run_id=row["run_id"],
            trace_id=row["trace_id"], approver=decided_by,
        )
        return public_approval(self.store.decide_approval(approval_id, status, decided_by, audit_event))

    def resume(self, approval_id: str, resume_token: str,
               continue_from_checkpoint: Callable[[str], object]):
        row = self.store.approval(approval_id)
        if row is None:
            raise KeyError(approval_id)
        if row["status"] != "APPROVED" or row["resumed_at"] is not None:
            raise ValueError("run is not resumable")
        if row["run_id"] is None or row["checkpoint_ref"] is None:
            raise ValueError("approval has no suspended run")
        if row["resume_token"] != resume_token:
            raise ValueError("invalid resume token")
        resumed_event = self.audit.prepare(
            agent=row["agent_name"], action="run.resume", risk=row["risk"],
            decision="approved", resource=row["subject_ref"],
            run_id=row["run_id"], trace_id=row["trace_id"],
            approver=row["decided_by"],
        )
        # High-risk audit must be durable before the business callback executes.
        tool_event = self.audit.prepare(
            agent=row["agent_name"], action="tool.call", risk=row["risk"],
            decision="approved", resource=row["subject_ref"],
            run_id=row["run_id"], trace_id=row["trace_id"],
            approver=row["decided_by"],
        )
        self.store.append_events([resumed_event, tool_event])
        result = continue_from_checkpoint(row["checkpoint_ref"])
        self.store.mark_resumed(approval_id)
        return result

    def list(self, *, status: str | None = None, agent: str | None = None,
             page: int = 1, size: int = 20) -> dict:
        rows, total = self.store.list_approvals(status, agent, page, size)
        return {"page": page, "size": size, "total": total,
                "items": [public_approval(row) for row in rows]}
