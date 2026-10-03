"""Per-invocation context and event emission points."""

import asyncio
import inspect
import os
import secrets
import re
from datetime import datetime
from typing import Any

from opentelemetry import trace

from keel.audit.reporter import AuditReporter
from keel.knowledge import KnowledgeClient
from keel.llm.client import LlmClient
from keel.protocol.errors import ErrorCode, KeelError
from keel.tracing import attrs

from keel.protocol.events import FinalEvent, StepEvent, SuspendEvent, TokenEvent, ToolEvent


class _UnimplementedClient:
    def __init__(self, name: str):
        self.name = name

    def __getattr__(self, method: str):
        raise NotImplementedError(f"ctx.{self.name}.{method} is implemented in P0-8")

    async def aclose(self):
        pass


class _ToolClient:
    def __init__(self, functions: dict, manifest, owner: "Context"):
        self.functions = functions
        self.manifest = manifest
        self.owner = owner

    async def call(self, name: str, **kwargs):
        callback = self.functions.get(name)
        declared = next((tool for tool in (self.manifest.spec.tools or []) if tool.name == name), None)
        if callback is None or declared is None:
            raise KeelError(ErrorCode.TOOL_NOT_GRANTED)
        if self.owner.audit is None:
            raise KeelError(ErrorCode.AUDIT_WRITE_FAILED)
        risk = declared.risk.value if declared.risk else "low"
        approval = declared.approval.value if declared.approval else "none"
        if risk == "high" and approval == "required" and not self.owner.resuming:
            return await self.owner.suspend_for_approval(name, kwargs)
        decision = "approved" if self.owner.resuming else "allowed"
        await self.owner.audit.record(
            "tool.call", risk, decision, payload=kwargs, resource=name,
            trace_id=self.owner.trace_id, run_id=self.owner.run_id,
            approver="local" if self.owner.resuming and risk == "high" else None)
        if inspect.iscoroutinefunction(callback):
            return await callback(**kwargs)
        return await asyncio.to_thread(callback, **kwargs)


class Context:
    def __init__(self, agent: str, run_id: str, trace_id: str, events: asyncio.Queue, tracer=None,
                 *, manifest=None, tool_functions=None, children=None, budget=None,
                 env: str | None = None, llm_client=None, knowledge_client=None,
                 audit_reporter=None):
        self.agent = agent
        self.run_id = run_id
        self.trace_id = trace_id
        self._events = events
        self.manifest = manifest
        self.resuming = False
        self.pending: dict | None = None
        self.budget = dict(budget or {"cny": 1.0, "steps": 8})
        self._children = children or {}
        self._tracer = tracer or trace.get_tracer("keel.agent")
        models = manifest.spec.models if manifest else None
        knowledge = manifest.spec.knowledge if manifest else None
        tool_specs = manifest.spec.tools if manifest else None
        audit_spec = manifest.spec.audit if manifest else None
        self.llm = llm_client or (LlmClient(agent, models.default_ if models else None,
                                            trace_id, self._tracer) if models else _UnimplementedClient("llm"))
        self.knowledge = knowledge_client or (KnowledgeClient(agent, self._tracer)
                                              if knowledge else _UnimplementedClient("knowledge"))
        self.audit = audit_reporter or (AuditReporter(
            agent, env, audit_spec.captureFields or () if audit_spec else ())
            if (tool_specs or audit_spec) and env else None)
        self.tools = (_ToolClient(tool_functions or {}, manifest, self)
                      if manifest else _UnimplementedClient("tools"))

    def step(self, name: str, status: str = "ok") -> None:
        self._span(name, "agent", status)
        self._events.put_nowait(StepEvent(name=name, status=status))

    def tool(self, name: str, status: str = "ok") -> None:
        self._span(name, "tool", status)
        self._events.put_nowait(ToolEvent(name=name, status=status))

    def _span(self, name: str, kind: str, status: str) -> None:
        if not re.fullmatch(r"[a-zA-Z0-9_.:-]{1,100}", name):
            raise ValueError("span name must be an identifier, not user content")
        if status not in attrs.STATUS_VALUES:
            raise ValueError("invalid span status")
        with self._tracer.start_as_current_span(name, attributes={
            attrs.OBSERVATION_TYPE: kind, attrs.AGENT: self.agent,
            attrs.RUN_ID: self.run_id, attrs.STATUS: status,
        }):
            pass

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

    async def suspend_for_approval(self, tool: str, kwargs: dict) -> SuspendEvent:
        import json
        from pathlib import Path
        import httpx
        folder = Path(".keel/checkpoints")
        folder.mkdir(parents=True, exist_ok=True)
        ref = f"ckpt_{self.run_id}"
        (folder / ref).write_text(json.dumps({"tool": tool, "kwargs": kwargs}), encoding="utf-8")
        url = os.environ.get("KEEL_AUDIT_URL")
        token = os.environ.get("KEEL_AUDIT_TOKEN")
        if not url or not token:
            raise KeelError(ErrorCode.AUDIT_WRITE_FAILED)
        try:
            async with httpx.AsyncClient(base_url=url, timeout=5,
                                         headers={"Authorization": f"Bearer {token}"}) as client:
                response = await client.post("/api/v1/approvals", json={
                    "subjectType": "tool.call", "subjectRef": tool, "agent": self.agent,
                    "summary": f"批准调用 {tool}?", "risk": "HIGH", "runId": self.run_id,
                    "checkpointRef": ref, "traceId": self.trace_id,
                })
                response.raise_for_status()
                approval_id = response.json()["data"]["id"]
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            raise KeelError(ErrorCode.AUDIT_WRITE_FAILED) from exc
        event = self.suspend("approval", ref=approval_id, prompt=f"批准调用 {tool}?")
        self.pending = {"tool": tool, "kwargs": kwargs, "checkpoint_ref": ref,
                        "approval_id": approval_id, "input_text": kwargs.get("title") or kwargs.get("text")}
        return event

    async def delegate(self, name: str, text: str):
        declared = list(self.manifest.spec.delegates or []) if self.manifest else []
        if name not in declared:
            raise KeelError(ErrorCode.DELEGATE_NOT_DECLARED)
        child = self._children.get(name)
        if child is None:
            raise KeelError(ErrorCode.DELEGATE_NOT_DECLARED)
        if self.budget.get("steps", 0) < 1 or self.budget.get("cny", 0) <= 0:
            raise KeelError(ErrorCode.GW_QUOTA_EXCEEDED)
        child_budget = {"cny": round(self.budget["cny"] - 0.1, 2), "steps": self.budget["steps"] - 1}
        self.budget = child_budget
        self._span(name, "agent", "ok")
        result = child(text, child_budget, self.trace_id)
        if inspect.isawaitable(result):
            result = await result
        return {"result": result, "budget": dict(child_budget), "trace_id": self.trace_id, "agent": name}

    async def gather(self, *args, **kwargs):
        raise NotImplementedError("ctx.gather is implemented after P0-6")

    async def aclose(self):
        for client in (self.llm, self.knowledge, self.audit):
            if client is not None and hasattr(client, "aclose"):
                await client.aclose()
