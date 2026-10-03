"""LangChain/LangGraph callback methods mapped to OTel child spans."""

import re
from uuid import UUID

from opentelemetry import trace

from keel.tracing import attrs


class LangGraphCallback:
    ignore_chain = False
    ignore_tool = False
    ignore_retriever = False
    ignore_llm = False
    raise_error = False
    run_inline = False

    def __init__(self, agent: str, tracer=None):
        self.agent = agent
        self.tracer = tracer or trace.get_tracer("keel.langgraph")
        self._spans: dict[str, trace.Span] = {}

    def _start(self, kind: str, name: str, run_id: UUID | str,
               parent_run_id: UUID | str | None = None):
        # Callback payloads may contain prompts and tool inputs; only stable names pass.
        safe_name = re.sub(r"[^a-zA-Z0-9_.:-]", "_", str(name))[:100] or kind
        parent = self._spans.get(str(parent_run_id))
        parent_context = trace.set_span_in_context(parent) if parent else None
        span = self.tracer.start_span(safe_name, context=parent_context,
                                      attributes={attrs.OBSERVATION_TYPE: kind,
                                                  attrs.AGENT: self.agent,
                                                  attrs.STATUS: "ok"})
        self._spans[str(run_id)] = span

    def _end(self, run_id: UUID | str, failed: bool = False):
        span = self._spans.pop(str(run_id), None)
        if span is not None:
            if failed:
                span.set_attribute(attrs.STATUS, "failed")
            span.end()

    def on_chain_start(self, serialized, inputs, *, run_id, parent_run_id=None, **kwargs):
        serialized = serialized or {}
        self._start("agent", serialized.get("name") or serialized.get("id", ["agent"])[-1],
                    run_id, parent_run_id)

    def on_chain_end(self, outputs, *, run_id, **kwargs):
        self._end(run_id)

    def on_chain_error(self, error, *, run_id, **kwargs):
        self._end(run_id, failed=True)

    def on_tool_start(self, serialized, input_str, *, run_id, parent_run_id=None, **kwargs):
        serialized = serialized or {}
        self._start("tool", serialized.get("name", "tool"), run_id, parent_run_id)

    def on_tool_end(self, output, *, run_id, **kwargs):
        self._end(run_id)

    def on_tool_error(self, error, *, run_id, **kwargs):
        self._end(run_id, failed=True)

    def on_retriever_start(self, serialized, query, *, run_id, parent_run_id=None, **kwargs):
        serialized = serialized or {}
        self._start("retriever", serialized.get("name", "retriever"), run_id, parent_run_id)

    def on_retriever_end(self, documents, *, run_id, **kwargs):
        self._end(run_id)

    def on_retriever_error(self, error, *, run_id, **kwargs):
        self._end(run_id, failed=True)

    def on_llm_start(self, serialized, prompts, *, run_id, parent_run_id=None, **kwargs):
        serialized = serialized or {}
        self._start("generation", serialized.get("name", "llm"), run_id, parent_run_id)

    def on_chat_model_start(self, serialized, messages, *, run_id, parent_run_id=None, **kwargs):
        self.on_llm_start(serialized, (), run_id=run_id, parent_run_id=parent_run_id)

    def on_llm_new_token(self, token, *, run_id, **kwargs):
        pass

    def on_llm_end(self, response, *, run_id, **kwargs):
        self._end(run_id)

    def on_llm_error(self, error, *, run_id, **kwargs):
        self._end(run_id, failed=True)
