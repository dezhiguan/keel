package com.keel.starter.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.springframework.util.StringUtils;

/**
 * Same preference as careermate: SkyWalking agent context when that toolkit is on the classpath,
 * otherwise the current OTel span. The starter does not depend on SkyWalking.
 */
public class TraceIdResolver {
    private static final String SKYWALKING_IGNORED_TRACE = "Ignored_Trace";

    public String resolveTraceId() {
        String skyWalkingTraceId = skyWalkingTraceId();
        if (isUsableTraceId(skyWalkingTraceId)) return skyWalkingTraceId;
        SpanContext context = currentContext();
        if (context == null || !context.isValid()) return null;
        return context.getTraceId();
    }

    public String resolveSpanId() {
        SpanContext context = currentContext();
        if (context != null && context.isValid() && StringUtils.hasText(context.getSpanId())) {
            return context.getSpanId();
        }
        return null;
    }

    private static SpanContext currentContext() {
        Span span = Span.current();
        return span == null ? null : span.getSpanContext();
    }

    private static String skyWalkingTraceId() {
        try {
            Class<?> type = Class.forName("org.apache.skywalking.apm.toolkit.trace.TraceContext");
            Object value = type.getMethod("traceId").invoke(null);
            return value == null ? null : value.toString();
        } catch (ClassNotFoundException ex) {
            return null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static boolean isUsableTraceId(String traceId) {
        return StringUtils.hasText(traceId)
                && !SKYWALKING_IGNORED_TRACE.equals(traceId)
                && !"N/A".equalsIgnoreCase(traceId);
    }
}
