package com.keel.starter.tracing;

/** Attribute keys from contracts/trace-attributes.md. Same set as the Python SDK. */
public final class SpanAttributes {
    public static final String OBSERVATION_TYPE = "langfuse.observation.type";
    public static final String OBSERVATION_INPUT = "langfuse.observation.input";
    public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";
    public static final String PROMPT_NAME = "langfuse.observation.prompt.name";
    public static final String PROMPT_VERSION = "langfuse.observation.prompt.version";
    public static final String SESSION_ID = "langfuse.session.id";
    public static final String USER_ID = "langfuse.user.id";
    public static final String ENVIRONMENT = "langfuse.environment";
    public static final String TRACE_TAGS = "langfuse.trace.tags";
    public static final String AGENT = "keel.agent";
    public static final String ENV = "keel.env";
    public static final String PROMPT_FALLBACK = "keel.prompt.fallback";
    public static final String AGENT_VERSION = "keel.agent.version";
    public static final String PARENT_AGENT = "keel.parent_agent";
    public static final String STATUS = "keel.status";
    public static final String AUDIT_IDS = "keel.audit_ids";
    public static final String FALLBACK_FROM = "keel.fallback_from";
    public static final String LLM_KEY_ALIAS = "keel.llm.key_alias";
    public static final String LLM_REQUEST_ID = "keel.llm.request_id";
    public static final String TOOL_DEPRECATED = "keel.tool.deprecated";
    public static final String RUN_ID = "keel.run.id";
    public static final String RUN_SUSPENDED = "keel.run.suspended";
    public static final String RUN_WAIT_MS = "keel.run.wait_ms";
    public static final String DEVFLOW_JOB_ID = "keel.devflow.job_id";
    public static final String DEVFLOW_LABEL = "keel.devflow.label";
    public static final String MODEL = "gen_ai.request.model";
    public static final String INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

    private SpanAttributes() {}
}
