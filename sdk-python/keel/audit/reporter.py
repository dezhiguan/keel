"""Audit delivery: high risk synchronously; other events durably queued."""

import asyncio
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

import httpx

from keel.audit.masking import capture_fields
from keel.protocol.errors import ErrorCode, KeelError


class AuditReporter:
    def __init__(self, agent: str, env: str, allowed_fields=(), *,
                 publisher=None, spool_path: str | Path | None = None,
                 queue_size: int = 128, http_client: httpx.AsyncClient | None = None):
        url = os.environ.get("KEEL_AUDIT_URL")
        token = os.environ.get("KEEL_AUDIT_TOKEN")
        if not url or not token:
            raise RuntimeError("KEEL_AUDIT_URL and KEEL_AUDIT_TOKEN are required")
        chosen_spool_path = spool_path or os.environ.get("KEEL_AUDIT_SPOOL_PATH")
        if not chosen_spool_path:
            raise RuntimeError("KEEL_AUDIT_SPOOL_PATH is required")
        if queue_size < 1:
            raise ValueError("queue_size must be positive")
        self.agent = agent
        self.env = env
        self.allowed_fields = tuple(allowed_fields)
        self.publisher = publisher
        self.spool_path = Path(chosen_spool_path)
        self.queue = asyncio.Queue(maxsize=queue_size)
        self._lock = asyncio.Lock()
        self.client = http_client or httpx.AsyncClient(
            base_url=url, headers={"Authorization": f"Bearer {token}"}, timeout=5)

    async def record(self, action: str, risk: str, decision: str, *,
                     payload: dict | None = None, resource: str | None = None,
                     trace_id: str | None = None, run_id: str | None = None):
        event = {"event_id": uuid4().hex,
                 "ts": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                 "agent": self.agent, "env": self.env, "action": action,
                 "risk": risk, "decision": decision,
                 "payload": capture_fields(payload or {}, self.allowed_fields)}
        if resource is not None:
            event["resource"] = resource
        if trace_id is not None:
            event["trace_id"] = trace_id
        if run_id is not None:
            event["run_id"] = run_id

        if risk == "high":
            try:
                response = await self.client.post("/api/v1/audit/events", json=event)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                raise KeelError(ErrorCode.AUDIT_WRITE_FAILED) from exc
            return event

        # Persist first: queue saturation or process shutdown cannot lose an audit event.
        async with self._lock:
            await asyncio.to_thread(self._append, event)
            try:
                self.queue.put_nowait(event["event_id"])
            except asyncio.QueueFull:
                pass
        return event

    def _append(self, event: dict):
        self.spool_path.parent.mkdir(parents=True, exist_ok=True)
        with self.spool_path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
            file.flush()
            os.fsync(file.fileno())

    async def replay_once(self) -> int:
        if self.publisher is None:
            return 0  # P1-11 supplies the real RocketMQ publisher.
        sent = 0
        async with self._lock:
            if not self.spool_path.exists():
                return 0
            lines = self.spool_path.read_text(encoding="utf-8").splitlines()
            while lines:
                event = json.loads(lines[0])
                try:
                    await self.publisher(event, self.agent)
                except Exception:
                    break
                lines.pop(0)
                temporary = self.spool_path.with_suffix(self.spool_path.suffix + ".tmp")
                with temporary.open("w", encoding="utf-8") as file:
                    file.write("\n".join(lines) + ("\n" if lines else ""))
                    file.flush()
                    os.fsync(file.fileno())
                temporary.replace(self.spool_path)
                sent += 1
            while not self.queue.empty():
                self.queue.get_nowait()
        return sent

    async def replay_forever(self, interval: float = 5):
        while True:
            await self.replay_once()
            await asyncio.sleep(interval)

    async def aclose(self):
        await self.client.aclose()
