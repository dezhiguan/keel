package com.keel.starter.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.StringUtils;

/** Propagates W3C traceparent plus the careermate request and session headers. */
public class TraceHeaderPropagator {
    private final TraceIdResolver traceIdResolver;

    public TraceHeaderPropagator(TraceIdResolver traceIdResolver) {
        this.traceIdResolver = traceIdResolver;
    }

    public void inject(HttpRequest.Builder builder) {
        if (builder == null) return;
        Map<String, String> carrier = new LinkedHashMap<>();
        inject(carrier);
        carrier.forEach(builder::header);
    }

    public void inject(Map<String, String> carrier) {
        if (carrier == null) return;
        syncMdcTraceId();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        SpanContext context = Span.current().getSpanContext();
        if (context.isValid() && !hasTraceparent(carrier)) {
            carrier.put("traceparent", "00-" + context.getTraceId() + "-" + context.getSpanId() + "-01");
        }
        String requestId = MDC.get(MdcKeys.REQUEST_ID);
        if (StringUtils.hasText(requestId)) carrier.putIfAbsent(MdcKeys.HEADER_REQUEST_ID, requestId);
        String sessionId = MDC.get(MdcKeys.SESSION_ID);
        if (StringUtils.hasText(sessionId)) carrier.putIfAbsent(MdcKeys.HEADER_SESSION_ID, sessionId);
    }

    public void inject(BiConsumer<String, String> headerSetter) {
        if (headerSetter == null) return;
        Map<String, String> carrier = new LinkedHashMap<>();
        inject(carrier);
        carrier.forEach(headerSetter);
    }

    public ClientHttpRequestInterceptor clientHttpRequestInterceptor() {
        return (request, body, execution) -> {
            inject((name, value) -> request.getHeaders().set(name, value));
            return execution.execute(request, body);
        };
    }

    public String currentTraceId() {
        String resolved = traceIdResolver.resolveTraceId();
        if (StringUtils.hasText(resolved)) {
            syncMdcTraceId(resolved);
            return resolved;
        }
        SpanContext context = Span.current().getSpanContext();
        if (context.isValid()) {
            syncMdcTraceId(context.getTraceId());
            return context.getTraceId();
        }
        String mdcTraceId = MDC.get(MdcKeys.TRACE_ID);
        if (StringUtils.hasText(mdcTraceId)) return mdcTraceId;
        return MDC.get(MdcKeys.REQUEST_ID);
    }

    private void syncMdcTraceId() {
        String resolved = traceIdResolver.resolveTraceId();
        if (StringUtils.hasText(resolved)) syncMdcTraceId(resolved);
    }

    private static void syncMdcTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) return;
        MDC.put(MdcKeys.TRACE_ID, traceId);
        MDC.put(MdcKeys.TRACE_ID_SNAKE, traceId);
    }

    private static boolean hasTraceparent(Map<String, String> carrier) {
        return carrier.entrySet().stream()
                .anyMatch(entry -> "traceparent".equalsIgnoreCase(entry.getKey()) && StringUtils.hasText(entry.getValue()));
    }
}
