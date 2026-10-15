"""Audit writes for standalone mode. Events stay in the local keel-lite database."""

from keel.lite.audit import LiteAudit
from keel.lite.store import LiteStore


class LiteAuditReporter:
    def __init__(self, agent: str, env: str, allowed_fields=(), root: str = "."):
        self.agent = agent
        self.env = env
        self.lite = LiteAudit(LiteStore(root), allowed_fields)

    async def record(self, action: str, risk: str, decision: str, *,
                     payload: dict | None = None, resource: str | None = None,
                     trace_id: str | None = None, run_id: str | None = None,
                     approver: str | None = None):
        data = self.lite.prepare(
            agent=self.agent, action=action, risk=risk, decision=decision,
            resource=resource, payload=payload, run_id=run_id, trace_id=trace_id,
            approver=approver,
        )
        data["env"] = self.env
        return self.lite.store.append_event(data).model_dump(mode="json")

    async def aclose(self):
        return None
