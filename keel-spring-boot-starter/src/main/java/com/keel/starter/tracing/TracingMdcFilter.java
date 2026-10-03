package com.keel.starter.tracing;

import com.keel.starter.manifest.AgentRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Careermate's request MDC filter, plus an agent span that stays open until the SSE stream finishes.
 * The span is not ended in {@code finally} when the request has gone async: that return only means
 * the controller handed back an {@code SseEmitter}.
 */
public class TracingMdcFilter extends OncePerRequestFilter {
    private final TraceIdResolver traceIdResolver;
    private final Tracer tracer;
    private final AgentRegistry registry;
    private final String serviceName;

    public TracingMdcFilter(TraceIdResolver traceIdResolver, Tracer tracer, AgentRegistry registry, String serviceName) {
        this.traceIdResolver = traceIdResolver;
        this.tracer = tracer;
        this.registry = registry;
        this.serviceName = serviceName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        String requestId = resolveRequestId(request);
        String sessionId = request.getHeader(MdcKeys.HEADER_SESSION_ID);
        String agent = resolveAgent(request);
        Span span = tracer.spanBuilder(spanName(request))
                .setAttribute(SpanAttributes.OBSERVATION_TYPE, "agent")
                .setAttribute(SpanAttributes.AGENT, agent)
                .setAttribute(SpanAttributes.STATUS, "ok")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            MDC.put(MdcKeys.REQUEST_ID, requestId);
            MDC.put(MdcKeys.SERVICE, serviceName);
            MDC.put(MdcKeys.AGENT, agent);
            if (StringUtils.hasText(sessionId)) {
                MDC.put(MdcKeys.SESSION_ID, sessionId.trim());
                span.setAttribute(SpanAttributes.SESSION_ID, sessionId.trim());
            }
            syncTraceMdc();
            if (isStreamingRequest(request)) {
                writeTraceHeaders(response, requestId);
                filterChain.doFilter(request, response);
                finishSpanWhenStreamEnds(request, span);
                return;
            }
            ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
            try {
                filterChain.doFilter(request, wrapper);
            } finally {
                syncTraceMdc();
                writeTraceHeaders(wrapper, requestId);
                wrapper.copyBodyToResponse();
            }
        } finally {
            if (!request.isAsyncStarted()) span.end();
            restoreMdc(previousMdc);
        }
    }

    private void finishSpanWhenStreamEnds(HttpServletRequest request, Span span) {
        if (!request.isAsyncStarted()) return;
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) { span.end(); }
                @Override public void onTimeout(AsyncEvent event) { span.end(); }
                @Override public void onError(AsyncEvent event) {
                    span.setAttribute(SpanAttributes.STATUS, "failed");
                    span.end();
                }
                @Override public void onStartAsync(AsyncEvent event) {}
            });
        } catch (IllegalStateException alreadyComplete) {
            span.end();
        }
    }

    private static String spanName(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.endsWith("/messages/stream")) return "agent.stream";
        return "agent.run";
    }

    private String resolveAgent(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String[] parts = uri == null ? new String[0] : uri.split("/");
        if (parts.length >= 3 && "v1".equals(parts[2]) && !"v1".equals(parts[1])) return parts[1];
        if (registry != null && registry.single()) return registry.all().iterator().next().name();
        return serviceName;
    }

    private boolean isStreamingRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")) return true;
        String uri = request.getRequestURI();
        return uri != null && (uri.endsWith("/messages/stream") || uri.endsWith("/v1/invoke"));
    }

    private void writeTraceHeaders(HttpServletResponse response, String requestId) {
        response.setHeader(MdcKeys.HEADER_REQUEST_ID, requestId);
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        if (!StringUtils.hasText(traceId)) traceId = requestId;
        response.setHeader(MdcKeys.HEADER_TRACE_ID, traceId);
    }

    private static void restoreMdc(Map<String, String> previousMdc) {
        if (previousMdc == null) MDC.clear();
        else MDC.setContextMap(previousMdc);
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(MdcKeys.HEADER_REQUEST_ID);
        if (StringUtils.hasText(incoming)) return incoming.trim();
        return UUID.randomUUID().toString();
    }

    private void syncTraceMdc() {
        String traceId = traceIdResolver.resolveTraceId();
        if (!StringUtils.hasText(traceId)) traceId = MDC.get(MdcKeys.TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            String requestId = MDC.get(MdcKeys.REQUEST_ID);
            traceId = StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
        }
        MDC.put(MdcKeys.TRACE_ID, traceId);
        MDC.put(MdcKeys.TRACE_ID_SNAKE, traceId);
        String spanId = traceIdResolver.resolveSpanId();
        if (StringUtils.hasText(spanId)) MDC.put(MdcKeys.SPAN_ID, spanId);
    }
}
