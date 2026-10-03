package com.keel.starter.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Child spans for an agent run. Names must be identifiers: user text is not a valid span name
 * and is not accepted as an attribute.
 */
public final class GenAiSpans {
    private GenAiSpans() {}

    public static Span agent(Tracer tracer, String agent, String runId) {
        return start(tracer, "agent.run", "agent", agent, runId);
    }

    public static Span generation(Tracer tracer, String agent, String runId, String model) {
        Span span = start(tracer, "llm.chat", "generation", agent, runId);
        if (model != null) span.setAttribute(SpanAttributes.MODEL, model);
        return span;
    }

    public static Span tool(Tracer tracer, String name, String agent, String runId) {
        return start(tracer, name, "tool", agent, runId);
    }

    public static Span retriever(Tracer tracer, String agent, String runId) {
        return start(tracer, "knowledge.search", "retriever", agent, runId);
    }

    public static void recordUsage(Span span, long inputTokens, long outputTokens) {
        span.setAttribute(SpanAttributes.INPUT_TOKENS, inputTokens);
        span.setAttribute(SpanAttributes.OUTPUT_TOKENS, outputTokens);
    }

    private static Span start(Tracer tracer, String name, String observationType, String agent, String runId) {
        if (name == null || !name.matches("[a-zA-Z0-9_.:\\-]{1,100}")) {
            throw new IllegalArgumentException("span name must be an identifier, not user content");
        }
        var builder = tracer.spanBuilder(name)
                .setAttribute(SpanAttributes.OBSERVATION_TYPE, observationType)
                .setAttribute(SpanAttributes.STATUS, "ok");
        if (agent != null) builder.setAttribute(SpanAttributes.AGENT, agent);
        if (runId != null) builder.setAttribute(SpanAttributes.RUN_ID, runId);
        return builder.startSpan();
    }
}
