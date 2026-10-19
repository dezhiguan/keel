"""OpenAI-compatible client directed only at the configured LiteLLM proxy.

The LiteLLM Langfuse callback must stay disabled: this SDK owns generation spans.
"""

import json
import logging
import os
import asyncio
from dataclasses import dataclass
from typing import Any

import httpx
import openai
from opentelemetry import trace

from keel.protocol.errors import ErrorCode, KeelError
from keel.tracing import attrs

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class ChatResult:
    content: str
    tool_calls: tuple[ToolCall, ...]


def _openai_base(url: str | None) -> str:
    base = (url or "").rstrip("/")
    if base and not base.endswith("/v1"):
        base += "/v1"
    return base


def _observation_input(messages: list[dict]) -> str:
    """Keep roles. A flat join hides the user question behind the system prompt."""
    rendered = []
    for message in messages:
        content = message.get("content")
        if not isinstance(content, str):
            content = json.dumps(message, ensure_ascii=False)
        role = message.get("role")
        rendered.append({"role": role if isinstance(role, str) and role else "user", "content": content})
    return json.dumps(rendered, ensure_ascii=False)


class LlmClient:
    def __init__(self, agent: str, default_model: str | None, trace_id: str,
                 tracer=None, http_client: httpx.AsyncClient | None = None, on_token=None, guard=None):
        from keel.runtime import standalone

        base_url = _openai_base(os.environ.get("KEEL_LLM_BASE_URL"))
        self.standalone = standalone()
        api_key = os.environ.get("KEEL_LLM_KEY")
        if self.standalone and not api_key:
            api_key = "standalone"
        if not base_url or not api_key:
            raise RuntimeError("KEEL_LLM_BASE_URL and KEEL_LLM_KEY are required")
        self.agent = agent
        self.default_model = default_model
        self.trace_id = trace_id
        self.on_token = on_token
        self.guard = guard
        self.tracer = tracer or trace.get_tracer("keel.llm")
        headers = {"X-Trace-Id": trace_id} if trace_id else None
        self.client = openai.AsyncOpenAI(base_url=base_url, api_key=api_key, max_retries=0,
                                         http_client=http_client, default_headers=headers)

    async def chat(self, messages: list[dict], model: str | None = None, prompt=None,
                   tools: list[dict] | None = None) -> str | ChatResult:
        chosen = model or self.default_model
        if not chosen:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "No LiteLLM model alias configured")
        if self.guard is not None:
            messages = self.guard.prepare_messages(messages)
        attributes = {
            attrs.OBSERVATION_TYPE: "generation", attrs.AGENT: self.agent,
            attrs.MODEL: chosen, attrs.STATUS: "ok",
            attrs.OBSERVATION_INPUT: _observation_input(messages),
        }
        if prompt is not None and getattr(prompt, "fallback", False):
            attributes[attrs.PROMPT_FALLBACK] = True
        elif prompt is not None and getattr(prompt, "version", None) is not None:
            attributes[attrs.PROMPT_NAME] = prompt.name
            attributes[attrs.PROMPT_VERSION] = int(prompt.version)
        with self.tracer.start_as_current_span("llm.chat", attributes=attributes) as span:
            try:
                options = {}
                config = getattr(prompt, "config", None) or {}
                if "temperature" in config:
                    options["temperature"] = config["temperature"]
                if "max_tokens" in config:
                    options["max_tokens"] = config["max_tokens"]
                if "top_p" in config:
                    options["top_p"] = config["top_p"]
                if tools is not None:
                    options["tools"] = tools
                if not self.standalone:
                    options["extra_body"] = {"metadata": {"caller_agent": self.agent}}
                if self.on_token is not None:
                    reply, calls, usage = await self._stream(chosen, messages, options)
                    self._record_usage(span, usage)
                    span.set_attribute(attrs.OBSERVATION_OUTPUT, reply)
                    if tools is None:
                        return reply
                    return ChatResult(reply, tuple(calls))
                try:
                    response = await self.client.chat.completions.create(
                        model=chosen, messages=messages, **options)
                except openai.APIStatusError as first:
                    if first.status_code not in (500, 502, 503, 504):
                        raise
                    await asyncio.sleep(0.2)
                    response = await self.client.chat.completions.create(
                        model=chosen, messages=messages, **options)
            except KeelError:
                span.set_attribute(attrs.STATUS, "failed")
                raise
            except openai.RateLimitError as exc:
                span.set_attribute(attrs.STATUS, "failed")
                raise KeelError(ErrorCode.GW_QUOTA_EXCEEDED) from exc
            except openai.APIStatusError as exc:
                span.set_attribute(attrs.STATUS, "failed")
                target = "模型服务" if self.standalone else "薄网关"
                detail = _error_detail(exc)
                message = f"{target} HTTP {exc.status_code}"
                if detail:
                    message = f"{message}：{detail}"
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, message) from exc
            except (openai.APIConnectionError, openai.APITimeoutError) as exc:
                span.set_attribute(attrs.STATUS, "failed")
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR) from exc
            usage = response.usage
            token_count = 0
            if usage:
                span.set_attribute(attrs.INPUT_TOKENS, usage.prompt_tokens)
                span.set_attribute(attrs.OUTPUT_TOKENS, usage.completion_tokens)
                token_count = usage.total_tokens
            extras = response.model_extra or {}
            cost = extras.get("response_cost", extras.get("cost", 0))
            if token_count and not cost and not self.standalone:
                logger.warning("LiteLLM reported zero cost for nonzero tokens trace_id=%s agent=%s",
                               self.trace_id, self.agent)
            reply = response.choices[0].message.content or ""
            span.set_attribute(attrs.OBSERVATION_OUTPUT, reply)
            if tools is None:
                return reply
            calls = []
            for call in response.choices[0].message.tool_calls or []:
                try:
                    arguments = json.loads(call.function.arguments)
                except (TypeError, ValueError) as exc:
                    span.set_attribute(attrs.STATUS, "failed")
                    raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, "模型工具参数不是有效 JSON") from exc
                if not isinstance(arguments, dict):
                    span.set_attribute(attrs.STATUS, "failed")
                    raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, "模型工具参数必须是 JSON 对象")
                calls.append(ToolCall(call.id, call.function.name, arguments))
            return ChatResult(reply, tuple(calls))

    async def _stream(self, model: str, messages: list[dict], options: dict):
        """Read the gateway SSE and emit each content delta before the call returns. """
        stream_options = {**options, "stream": True, "stream_options": {"include_usage": True}}
        try:
            stream = await self.client.chat.completions.create(
                model=model, messages=messages, **stream_options)
        except openai.APIStatusError as first:
            if first.status_code not in (500, 502, 503, 504):
                raise
            await asyncio.sleep(0.2)
            stream = await self.client.chat.completions.create(
                model=model, messages=messages, **stream_options)
        parts: list[str] = []
        tools: dict[int, dict[str, str]] = {}
        usage = None
        async for chunk in stream:
            if getattr(chunk, "usage", None):
                usage = chunk.usage
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            if delta is None:
                continue
            if delta.content:
                parts.append(delta.content)
                self.on_token(delta.content)
            for call in delta.tool_calls or []:
                slot = tools.setdefault(call.index, {"id": "", "name": "", "arguments": ""})
                if call.id:
                    slot["id"] = call.id
                function = call.function
                if function and function.name:
                    slot["name"] += function.name
                if function and function.arguments:
                    slot["arguments"] += function.arguments
        parsed = []
        for index in sorted(tools):
            slot = tools[index]
            try:
                arguments = json.loads(slot["arguments"] or "{}")
            except (TypeError, ValueError) as exc:
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, "模型工具参数不是有效 JSON") from exc
            if not isinstance(arguments, dict):
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, "模型工具参数必须是 JSON 对象")
            parsed.append(ToolCall(slot["id"], slot["name"], arguments))
        return "".join(parts), parsed, usage

    def _record_usage(self, span, usage) -> None:
        if not usage:
            return
        span.set_attribute(attrs.INPUT_TOKENS, usage.prompt_tokens)
        span.set_attribute(attrs.OUTPUT_TOKENS, usage.completion_tokens)

    async def aclose(self):
        await self.client.close()


def _error_detail(exc: openai.APIStatusError) -> str:
    body = getattr(exc, "body", None)
    message = ""
    if isinstance(body, dict):
        raw = body.get("message")
        if not isinstance(raw, str) or not raw.strip():
            error = body.get("error")
            if isinstance(error, dict):
                raw = error.get("message")
            elif isinstance(error, str):
                raw = error
        message = raw if isinstance(raw, str) else ""
    elif isinstance(body, str):
        message = body
    message = " ".join(message.split())
    return message[:180]
