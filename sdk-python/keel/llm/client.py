"""OpenAI-compatible client directed only at the configured LiteLLM proxy.

The LiteLLM Langfuse callback must stay disabled: this SDK owns generation spans.
"""

import json
import logging
import os

import httpx
import openai
from opentelemetry import trace

from keel.protocol.errors import ErrorCode, KeelError
from keel.tracing import attrs

logger = logging.getLogger(__name__)


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
                 tracer=None, http_client: httpx.AsyncClient | None = None):
        base_url = _openai_base(os.environ.get("KEEL_LLM_BASE_URL"))
        api_key = os.environ.get("KEEL_LLM_KEY")
        if not base_url or not api_key:
            raise RuntimeError("KEEL_LLM_BASE_URL and KEEL_LLM_KEY are required")
        self.agent = agent
        self.default_model = default_model
        self.trace_id = trace_id
        self.tracer = tracer or trace.get_tracer("keel.llm")
        self.client = openai.AsyncOpenAI(base_url=base_url, api_key=api_key, max_retries=0,
                                         http_client=http_client)

    async def chat(self, messages: list[dict], model: str | None = None, prompt=None) -> str:
        chosen = model or self.default_model
        if not chosen:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "No LiteLLM model alias configured")
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
                response = await self.client.chat.completions.create(
                    model=chosen, messages=messages, **options,
                    extra_body={"metadata": {"caller_agent": self.agent}})
            except openai.RateLimitError as exc:
                span.set_attribute(attrs.STATUS, "failed")
                raise KeelError(ErrorCode.GW_QUOTA_EXCEEDED) from exc
            except openai.APIStatusError as exc:
                span.set_attribute(attrs.STATUS, "failed")
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR, f"薄网关 HTTP {exc.status_code}") from exc
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
            if token_count and not cost:
                logger.warning("LiteLLM reported zero cost for nonzero tokens trace_id=%s agent=%s",
                               self.trace_id, self.agent)
            reply = response.choices[0].message.content or ""
            span.set_attribute(attrs.OBSERVATION_OUTPUT, reply)
            return reply

    async def aclose(self):
        await self.client.close()
