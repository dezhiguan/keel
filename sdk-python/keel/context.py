"""Per-invocation context and event emission points."""

import asyncio
import inspect
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
    def __init__(self, functions: dict, manifest, reporter: AuditReporter | None,
                 trace_id: str, run_id: str):
        self.functions = functions
        self.manifest = manifest
        self.reporter = reporter
        self.trace_id = trace_id
        self.run_id = run_id

    async def call(self, name: str, **kwargs):
        callback = self.functions.get(name)
        declared = next((tool for tool in (self.manifest.spec.tools or []) if tool.name == name), None)
        if callback is None or declared is None:
            raise KeelError(ErrorCode.TOOL_NOT_GRANTED)
        if self.reporter is None:
            raise KeelError(ErrorCode.AUDIT_WRITE_FAILED)
        risk = declared.risk.value if declared.risk else "low"
        await self.reporter.record("tool.call", risk, "allowed", payload=kwargs,
                                   resource=name, trace_id=self.trace_id, run_id=self.run_id)
        if inspect.iscoroutinefunction(callback):
            return await callback(**kwargs)
        return await asyncio.to_thread(callback, **kwargs)


class Context:
    def __init__(self, agent: str, run_id: str, trace_id: str, events: asyncio.Queue, tracer=None,
                 *, manifest=None, tool_functions=None, env: str | None = None,
                 llm_client=None, knowledge_client=None, audit_reporter=None):
        self.agent = agent
        self.run_id = run_id
        self.trace_id = trace_id
        self._events = events
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
        self.tools = (_ToolClient(tool_functions or {}, manifest, self.audit, trace_id, run_id)
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

    async def delegate(self, *args, **kwargs):
        raise NotImplementedError("ctx.delegate is implemented after P0-6")

    async def gather(self, *args, **kwargs):
        raise NotImplementedError("ctx.gather is implemented after P0-6")

    async def aclose(self):
        for client in (self.llm, self.knowledge, self.audit):
            if client is not None and hasattr(client, "aclose"):
                await client.aclose()
