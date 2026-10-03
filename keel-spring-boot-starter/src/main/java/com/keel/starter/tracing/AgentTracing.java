package com.keel.starter.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * Careermate's span API: {@code agent.stream} for a streamed run, caller-chosen names otherwise.
 * Tags that are not in trace-attributes.md are not copied onto the exported span.
 */
public class AgentTracing {
    private final Tracer tracer;

    public AgentTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    public void run(String spanName, Long userId, String sessionId, Runnable runnable) {
        call(spanName, userId, sessionId, null, null, null, () -> {
            runnable.run();
            return null;
        });
    }

    public <T> T call(String spanName, Long userId, String sessionId, String toolName,
                      String provider, String model, Supplier<T> supplier) {
        String type = StringUtils.hasText(toolName) ? "tool" : StringUtils.hasText(model) ? "generation" : "agent";
        String agent = MDC.get(MdcKeys.AGENT);
        if (!StringUtils.hasText(agent)) agent = MDC.get(MdcKeys.SERVICE);
        Span span = GenAiSpans.tool(tracer, spanName, agent, null);
        if (!"tool".equals(type)) {
            span.setAttribute(SpanAttributes.OBSERVATION_TYPE, type);
        }
        if (StringUtils.hasText(sessionId)) span.setAttribute(SpanAttributes.SESSION_ID, sessionId);
        if (userId != null) span.setAttribute(SpanAttributes.USER_ID, String.valueOf(userId));
        if (StringUtils.hasText(model)) span.setAttribute(SpanAttributes.MODEL, model);
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get();
        } catch (RuntimeException | Error ex) {
            // Exception messages can contain user text, so only the status is recorded.
            span.setAttribute(SpanAttributes.STATUS, "failed");
            throw ex;
        } finally {
            span.end();
        }
    }

    public void runStream(Long userId, String sessionId, Runnable runnable) {
        run("agent.stream", userId, sessionId, runnable);
    }

    public Map<String, String> agentTags(Long userId, String sessionId) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (userId != null) tags.put("user.id", String.valueOf(userId));
        if (StringUtils.hasText(sessionId)) tags.put("agent.session_id", sessionId);
        return tags;
    }
}
