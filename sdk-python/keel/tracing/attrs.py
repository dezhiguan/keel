"""Allowed span attribute keys from contracts/trace-attributes.md."""

OBSERVATION_TYPE = "langfuse.observation.type"
OBSERVATION_INPUT = "langfuse.observation.input"
OBSERVATION_OUTPUT = "langfuse.observation.output"
PROMPT_NAME = "langfuse.observation.prompt.name"
PROMPT_VERSION = "langfuse.observation.prompt.version"
SESSION_ID = "langfuse.session.id"
USER_ID = "langfuse.user.id"
ENVIRONMENT = "langfuse.environment"
TRACE_TAGS = "langfuse.trace.tags"
AGENT = "keel.agent"
ENV = "keel.env"
PROMPT_FALLBACK = "keel.prompt.fallback"
AGENT_VERSION = "keel.agent.version"
PARENT_AGENT = "keel.parent_agent"
STATUS = "keel.status"
AUDIT_IDS = "keel.audit_ids"
FALLBACK_FROM = "keel.fallback_from"
LLM_KEY_ALIAS = "keel.llm.key_alias"
LLM_REQUEST_ID = "keel.llm.request_id"
TOOL_DEPRECATED = "keel.tool.deprecated"
RUN_ID = "keel.run.id"
RUN_SUSPENDED = "keel.run.suspended"
RUN_WAIT_MS = "keel.run.wait_ms"
MODEL = "gen_ai.request.model"
INPUT_TOKENS = "gen_ai.usage.input_tokens"
OUTPUT_TOKENS = "gen_ai.usage.output_tokens"

STATUS_VALUES = frozenset(("ok", "fallback", "failed"))


def askdb_status(value: str) -> str:
    """Map askdb/askdb/trace.py's OK_STATUSES and SOFT_STATUSES."""
    if value in ("ok", "hit"):
        return "ok"
    if value in ("fallback", "degraded", "empty"):
        return "fallback"
    return "failed"
