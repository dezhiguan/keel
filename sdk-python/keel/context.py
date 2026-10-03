"""Per-invocation context and event emission points."""

import asyncio
import secrets
from datetime import datetime
from typing import Any

from keel.protocol.events import FinalEvent, StepEvent, SuspendEvent, TokenEvent, ToolEvent


class _UnimplementedClient:
    def __init__(self, name: str):
        self.name = name

    def __getattr__(self, method: str):
        raise NotImplementedError(f"ctx.{self.name}.{method} is implemented in P0-8")


class Context:
    def __init__(self, agent: str, run_id: str, trace_id: str, events: asyncio.Queue):
        self.agent = agent
        self.run_id = run_id
        self.trace_id = trace_id
        self._events = events
        # P0-7/P0-8 replace these clients at the invocation boundary.
        self.llm = _UnimplementedClient("llm")
        self.knowledge = _UnimplementedClient("knowledge")
        self.tools = _UnimplementedClient("tools")

    def step(self, name: str, status: str = "ok") -> None:
        self._events.put_nowait(StepEvent(name=name, status=status))

    def tool(self, name: str, status: str = "ok") -> None:
        self._events.put_nowait(ToolEvent(name=name, status=status))

    def token(self, text: str) -> None:
        self._events.put_nowait(TokenEvent(text=text))

    def final(self, answer: str, citations: list[dict[str, Any]] | None = None,
              meta: dict[str, Any] | None = None) -> FinalEvent:
        return FinalEvent(answer=answer, citations=citations or [], meta=meta or {},
                          trace_id=self.trace_id, run_id=self.run_id)

    def suspend(self, reason: str, ref: str | None = None, prompt: str | None = None,
                deadline: datetime | None = None) -> SuspendEvent:
        return SuspendEvent(run_id=self.run_id, reason=reason, ref=ref, prompt=prompt,
                            deadline=deadline, resume_token=secrets.token_urlsafe(32),
                            trace_id=self.trace_id)

    async def delegate(self, *args, **kwargs):
        raise NotImplementedError("ctx.delegate is implemented after P0-6")

    async def gather(self, *args, **kwargs):
        raise NotImplementedError("ctx.gather is implemented after P0-6")
