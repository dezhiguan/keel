"""Per-invocation context and event emission points."""

import asyncio
import inspect
import json
import os
import secrets
import re
from datetime import datetime
from pathlib import Path
from typing import Any

from opentelemetry import trace

from keel.audit.reporter import AuditReporter
from keel.knowledge import KnowledgeClient
from keel.llm.client import LlmClient
from keel.llm.client import ChatResult
from keel.lite.memory import LiteMemory
from keel.lite.store import LiteStore
from keel.prompts import PromptClient, TrustedMessage
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
        declared = next((tool for tool in (self.manifest.spec.tools or []) if tool.name == name), None)
        if declared is None:
            raise KeelError(ErrorCode.TOOL_NOT_GRANTED)
        attributes = {attrs.OBSERVATION_TYPE: "tool", attrs.AGENT: self.owner.agent,
                      attrs.RUN_ID: self.owner.run_id, attrs.STATUS: "ok"}
        if self.owner.env:
            attributes[attrs.ENV] = self.owner.env
        with self.owner._tracer.start_as_current_span(name, attributes=attributes) as span:
            try:
                outcome = await self._execute(name, declared, kwargs)
            except Exception:
                span.set_attribute(attrs.STATUS, "failed")
                self.owner._events.put_nowait(ToolEvent(name=name, status="failed"))
                raise
            status = "fallback" if isinstance(outcome, SuspendEvent) else "ok"
            span.set_attribute(attrs.STATUS, status)
            self.owner._events.put_nowait(ToolEvent(name=name, status=status))
            return outcome

    async def _execute(self, name, declared, kwargs):
        callback = self.functions.get(name)
        if not self.owner.resuming and self.owner.guard.before_tool(declared):
            if self.owner.audit is None:
                raise KeelError(ErrorCode.AUDIT_WRITE_FAILED)
            await self.owner.audit.record("tool.call", "high", "pending", payload=kwargs,
                                          resource=name, trace_id=self.owner.trace_id, run_id=self.owner.run_id)
            return await self.owner.suspend_for_approval(name, kwargs)
        if callback is None:
            from keel.tools.remote import invoke
            outcome = await invoke(self.owner, name, kwargs)
            return outcome if isinstance(outcome, SuspendEvent) else self.owner.guard.filter_tool(outcome)
        if self.owner.audit is None:
            raise KeelError(ErrorCode.AUDIT_WRITE_FAILED)
        risk = declared.risk.value if declared.risk else "low"
        approval = declared.approval.value if declared.approval else "none"
        if risk == "high" and approval == "required" and not self.owner.resuming:
            await self.owner.audit.record("tool.call", risk, "pending", payload=kwargs,
                                          resource=name, trace_id=self.owner.trace_id, run_id=self.owner.run_id)
            return await self.owner.suspend_for_approval(name, kwargs)
        decision = "approved" if self.owner.resuming else "allowed"
        await self.owner.audit.record(
            "tool.call", risk, decision, payload=kwargs, resource=name,
            trace_id=self.owner.trace_id, run_id=self.owner.run_id,
            approver="local" if self.owner.resuming and risk == "high" else None)
        if inspect.iscoroutinefunction(callback):
            outcome = await callback(**kwargs)
        else:
            outcome = await asyncio.to_thread(callback, **kwargs)
        return self.owner.guard.filter_tool(outcome)


class Context:
    def __init__(self, agent: str, run_id: str, trace_id: str, events: asyncio.Queue, tracer=None,
                 *, manifest=None, tool_functions=None, children=None, budget=None,
                 env: str | None = None, llm_client=None, knowledge_client=None,
                 audit_reporter=None, session_id: str | None = None,
                 eval_run_id: str | None = None, session_messages: list[dict] | None = None):
        self.agent = agent
        self.env = env
        self.run_id = run_id
        self.trace_id = trace_id
        self.session_id = session_id
        self.eval_run_id = eval_run_id
        if session_messages is not None and (not isinstance(session_messages, list)
                or len(session_messages) > 6 or any(not isinstance(row, dict)
                or row.get("role") not in ("user", "assistant")
                or not isinstance(row.get("content"), str) or len(row["content"]) > 400
                for row in session_messages)):
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM)
        self._session_messages = ([{"role": row["role"], "content": row["content"]}
                                   for row in session_messages] if session_messages is not None else None)
        self._events = events
        self.manifest = manifest
        self.resuming = False
        self.delegation_token: str | None = None
        self.pending: dict | None = None
        self.budget = dict(budget or {"cny": 1.0, "steps": 8})
        self._children = children or {}
        self._tracer = tracer or trace.get_tracer("keel.agent")
        from keel.guard.injection import InvocationGuard
        self.guard = InvocationGuard(getattr(manifest.spec, "guardrails", None) if manifest else None,
                                     agent, env, self._tracer, events)
        models = manifest.spec.models if manifest else None
        knowledge = manifest.spec.knowledge if manifest else None
        tool_specs = manifest.spec.tools if manifest else None
        audit_spec = manifest.spec.audit if manifest else None
        self.llm = llm_client or (LlmClient(agent, models.default_ if models else None,
                                            trace_id, self._tracer, on_token=self.token, guard=self.guard)
                                  if models else _UnimplementedClient("llm"))
        self.knowledge = knowledge_client or (KnowledgeClient(agent, self._tracer, guard=self.guard)
                                              if knowledge else _UnimplementedClient("knowledge"))
        from keel.audit.lite_reporter import LiteAuditReporter
        from keel.runtime import standalone
        capture = audit_spec.captureFields or () if audit_spec else ()
        if audit_reporter is not None:
            self.audit = audit_reporter
        elif standalone():
            self.audit = LiteAuditReporter(agent, env, capture)
        else:
            self.audit = (AuditReporter(agent, env, capture)
                          if ((tool_specs and tool_functions) or audit_spec) and env else None)
        self.tools = (_ToolClient(tool_functions or {}, manifest, self)
                      if manifest else _UnimplementedClient("tools"))
        from keel.skills import SkillClient
        self.skills = SkillClient(manifest, agent, env) if manifest else _UnimplementedClient("skills")
        self._prompts = PromptClient(manifest, env) if manifest else None
        self._prompt_versions: dict[str, int] = {}
        self._prompt_fallback = False
        self.memory = LiteMemory(LiteStore(".") if session_id else None, agent, session_id, eval_run_id, guard=self.guard)
        from keel.memory_remote import UserMemory
        self.memory.bind_user(UserMemory(agent, env, eval_run_id))

    async def prompt(self, name: str):
        if self._prompts is None:
            raise KeelError(ErrorCode.PROMPT_NOT_DECLARED)
        prompt = await self._prompts.get(self.agent, name, self.trace_id)
        if getattr(prompt, "fallback", False):
            self._prompt_fallback = True
        elif getattr(prompt, "version", None) is not None:
            previous = self._prompt_versions.get(name)
            if previous is not None and previous != int(prompt.version):
                self._prompt_fallback = True
            self._prompt_versions[name] = int(prompt.version)
        return prompt

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
        safe = self.guard.token(text)
        if safe:
            self._events.put_nowait(TokenEvent(text=safe))

    def guard_event(self, event):
        if isinstance(event, TokenEvent):
            safe = self.guard.token(event.text)
            return TokenEvent(text=safe) if safe else None
        if isinstance(event, FinalEvent):
            tail = self.guard.final(event.answer)
            if tail:
                self._events.put_nowait(TokenEvent(text=tail))
        return event

    def final(self, answer: str, citations: list[dict[str, Any]] | None = None,
              meta: dict[str, Any] | None = None) -> FinalEvent:
        tail = self.guard.final(answer)
        if tail:
            self._events.put_nowait(TokenEvent(text=tail))
        evidence = {**(meta or {}), "prompt_versions": dict(self._prompt_versions),
                    "prompt_fallback": self._prompt_fallback}
        return FinalEvent(answer=answer, citations=citations or [], meta=evidence,
                          trace_id=self.trace_id, run_id=self.run_id)

    def suspend(self, reason: str, ref: str | None = None, prompt: str | None = None,
                deadline: datetime | None = None) -> SuspendEvent:
        return SuspendEvent(run_id=self.run_id, reason=reason, ref=ref, prompt=prompt,
                            deadline=deadline, resume_token=secrets.token_urlsafe(32),
                            trace_id=self.trace_id)

    async def suspend_for_approval(self, tool: str, kwargs: dict) -> SuspendEvent:
        import httpx
        folder = Path(".keel/checkpoints")
        folder.mkdir(parents=True, exist_ok=True)
        ref = f"ckpt_{self.run_id}"
        if not (folder / ref).exists():
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

    async def loop(self, messages: list[dict], tools: list[dict], *,
                   max_steps: int | None = None, prompt=None) -> FinalEvent | SuspendEvent:
        configured = getattr(getattr(getattr(self.manifest.spec, "models", None), "budget", None),
                             "maxSteps", None) if self.manifest else None
        limit = min(max_steps if max_steps is not None else configured or 6,
                    int(self.budget.get("steps", 8)))
        if limit < 1:
            raise KeelError(ErrorCode.AGENT_MAX_STEPS_EXCEEDED)
        checkpoint = Path(".keel/checkpoints") / f"ckpt_{self.run_id}"
        turn_messages = list(messages)
        if self.resuming:
            history = turn_messages
        else:
            system = [message for message in turn_messages if message.get("role") == "system"]
            turns = [message for message in turn_messages if message.get("role") != "system"]
            prior = self.memory.history() if self._session_messages is None else self._session_messages
            history = system + prior + turns
        step = 0
        last_call = None
        if self.resuming:
            try:
                state = json.loads(checkpoint.read_text(encoding="utf-8"))
                history = state["messages"]
                for index in state.get("trusted_messages", []):
                    if isinstance(index, int) and 0 <= index < len(history):
                        history[index] = TrustedMessage(history[index])
                turn_messages = state["turn_messages"]
                step = state["step"]
                limit = min(limit, state["limit"])
                last_call = state["last_call"]
                self.guard.tainted = bool(state.get("tainted"))
                pending = state["pending_calls"]
                if state["agent"] != self.agent or not pending:
                    raise ValueError("checkpoint does not match run")
            except (OSError, ValueError, KeyError, TypeError) as exc:
                raise KeelError(ErrorCode.RUN_NOT_RESUMABLE) from exc
            for index, call in enumerate(pending):
                if index > 0 and call["signature"] == last_call:
                    raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, "模型连续重复调用同一工具")
                if index > 0 and self._requires_approval(call["name"]):
                    self._save_loop(checkpoint, history, turn_messages, step, limit, last_call, pending[index:])
                outcome = await self.tools.call(call["name"], **call["arguments"])
                if index == 0:
                    self.resuming = False
                if isinstance(outcome, SuspendEvent):
                    self._save_loop(checkpoint, history, turn_messages, step, limit, last_call, pending[index:])
                    return outcome
                history.append({"role": "tool", "tool_call_id": call["id"],
                                "content": json.dumps(outcome, ensure_ascii=False, default=str)})
                last_call = call["signature"]
            checkpoint.unlink(missing_ok=True)

        while True:
            if self.budget.get("cny", 0) <= 0:
                raise KeelError(ErrorCode.GW_QUOTA_EXCEEDED)
            if step >= limit:
                raise KeelError(ErrorCode.AGENT_MAX_STEPS_EXCEEDED)
            step += 1
            self._events.put_nowait(StepEvent(name=f"agent.loop.step.{step}", status="ok"))
            with self._tracer.start_as_current_span("agent.loop.step", attributes={
                attrs.OBSERVATION_TYPE: "agent", attrs.AGENT: self.agent,
                attrs.RUN_ID: self.run_id, attrs.LOOP_STEP: step, attrs.STATUS: "ok",
            }) as span:
                try:
                    result = await self.llm.chat(history, tools=tools, prompt=prompt)
                    if not isinstance(result, ChatResult):
                        raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, "模型没有返回结构化工具结果")
                    if not result.tool_calls:
                        terminal = self.final(result.content)
                        if self.session_id and not self.eval_run_id and not self.guard.tainted:
                            for message in turn_messages:
                                if message.get("role") == "user" and isinstance(message.get("content"), str):
                                    self.memory.append("user", message["content"])
                            self.memory.append("assistant", result.content)
                        checkpoint.unlink(missing_ok=True)
                        return terminal
                    calls = [{"id": call.id, "name": call.name, "arguments": call.arguments,
                              "signature": json.dumps([call.name, call.arguments], ensure_ascii=False, sort_keys=True)}
                             for call in result.tool_calls]
                    history.append({"role": "assistant", "content": result.content,
                                    "tool_calls": [{"id": call["id"], "type": "function",
                                                    "function": {"name": call["name"],
                                                                 "arguments": json.dumps(call["arguments"], ensure_ascii=False)}}
                                                   for call in calls]})
                    for index, call in enumerate(calls):
                        if call["signature"] == last_call:
                            raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, "模型连续重复调用同一工具")
                        if self._requires_approval(call["name"]):
                            self._save_loop(checkpoint, history, turn_messages, step, limit, last_call, calls[index:])
                        outcome = await self.tools.call(call["name"], **call["arguments"])
                        if isinstance(outcome, SuspendEvent):
                            self._save_loop(checkpoint, history, turn_messages, step, limit, last_call, calls[index:])
                            return outcome
                        history.append({"role": "tool", "tool_call_id": call["id"],
                                        "content": json.dumps(outcome, ensure_ascii=False, default=str)})
                        last_call = call["signature"]
                except Exception:
                    span.set_attribute(attrs.STATUS, "failed")
                    raise

    def _save_loop(self, path: Path, messages: list[dict], turn_messages: list[dict], step: int, limit: int,
                   last_call: str | None, pending_calls: list[dict]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps({"agent": self.agent, "messages": messages,
                                         "turn_messages": turn_messages, "step": step,
                                         "limit": limit, "last_call": last_call,
                                         "pending_calls": pending_calls, "tainted": self.guard.tainted,
                                         "trusted_messages": [index for index, message in enumerate(messages)
                                                              if isinstance(message, TrustedMessage)]},
                                        ensure_ascii=False), encoding="utf-8")
        temporary.replace(path)

    def _requires_approval(self, name: str) -> bool:
        declared = next((tool for tool in (self.manifest.spec.tools or []) if tool.name == name), None)
        return bool(declared and not self.resuming and (self.guard.approval_required(declared)
                    or (declared.risk and declared.risk.value == "high"
                        and declared.approval and declared.approval.value == "required")))

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
