package com.keel.starter.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.common.model.ErrorEvent;
import com.keel.common.model.FinalEvent;
import com.keel.common.model.SuspendEvent;
import com.keel.starter.context.KeelContext;
import com.keel.starter.manifest.AgentBinding;
import com.keel.starter.manifest.AgentRegistry;
import com.keel.starter.tracing.MdcContext;
import com.keel.starter.tracing.SpanAttributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class InvokeController {
    private static final Logger log = LoggerFactory.getLogger(InvokeController.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final AgentRegistry registry;
    private final KeelSessions sessions;
    private final ObjectMapper mapper;

    public InvokeController(AgentRegistry registry, KeelSessions sessions, ObjectMapper mapper) {
        this.registry = registry;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public Object invoke(HttpServletRequest request) throws IOException {
        String traceId = traceId(request);
        Map<String, Object> body;
        try {
            body = mapper.readValue(request.getInputStream(), MAP);
        } catch (JsonProcessingException ex) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM);
        }
        if (!valid(body)) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM);
        // TODO(P3-1): persist idempotency_key and return the original run on retries.
        AgentBinding binding = registry.resolve(request);
        String runId = UUID.randomUUID().toString().replace("-", "");
        SseEmitter emitter = new SseEmitter(0L);
        sessions.begin(runId);
        Runnable end = () -> sessions.end(runId);
        emitter.onCompletion(end);
        emitter.onTimeout(end);
        emitter.onError(error -> end.run());
        // Virtual threads do not inherit MDC. Copy it, and the current span, before the request thread returns.
        Runnable task = MdcContext.wrap(() -> produce(emitter, binding, body, traceId, runId));
        Thread.ofVirtual().start(Context.current().wrap(task));
        return emitter;
    }

    public ResponseEntity<ErrorBody> run(HttpServletRequest request) {
        registry.resolve(request);
        ErrorCode code = ErrorCode.RUN_NOT_FOUND;
        return ResponseEntity.status(code.http()).body(ErrorBody.of(code, traceId(request), null));
    }

    private void produce(SseEmitter emitter, AgentBinding binding, Map<String, Object> body,
                         String traceId, String runId) {
        Span span = Span.current();
        span.setAttribute(SpanAttributes.RUN_ID, runId);
        span.setAttribute(SpanAttributes.AGENT, binding.name());
        KeelContext context = new KeelContext(binding.name(), runId, traceId, (name, event) -> send(emitter, name, event));
        try {
            Object result = binding.invoke(body, context);
            if (result instanceof SuspendEvent) span.setAttribute(SpanAttributes.RUN_SUSPENDED, true);
            if (result instanceof ErrorEvent) span.setAttribute(SpanAttributes.STATUS, "failed");
            if (result instanceof FinalEvent || result instanceof SuspendEvent || result instanceof ErrorEvent) {
                send(emitter, eventName(result), result);
            } else {
                log.error("entry returned no terminal event trace_id={} agent={}", traceId, binding.name());
                send(emitter, "error", errorEvent(ErrorCode.SERVER_INTERNAL_ERROR, traceId, runId));
            }
        } catch (UncheckedIOException ex) {
            log.error("SSE client disconnected trace_id={} agent={}", traceId, binding.name());
        } catch (KeelException ex) {
            span.setAttribute(SpanAttributes.STATUS, "failed");
            log.error("agent invocation rejected trace_id={} agent={}", traceId, binding.name());
            sendQuietly(emitter, "error", errorEvent(ex.code(), traceId, runId));
        } catch (Throwable ex) {
            span.setAttribute(SpanAttributes.STATUS, "failed");
            log.error("agent invocation failed trace_id={} agent={}", traceId, binding.name());
            sendQuietly(emitter, "error", errorEvent(ErrorCode.SERVER_INTERNAL_ERROR, traceId, runId));
        } finally {
            sessions.end(runId);
            complete(emitter);
        }
    }

    private void send(SseEmitter emitter, String name, Object event) {
        try {
            emitter.send(SseEmitter.event().name(name).data(mapper.writeValueAsString(event)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void sendQuietly(SseEmitter emitter, String name, Object event) {
        try {
            send(emitter, name, event);
        } catch (UncheckedIOException ignored) {
            // The client is already gone; the completion callback releases the run.
        }
    }

    private static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Disconnect or a previous complete already closed the emitter.
        }
    }

    private static ErrorEvent errorEvent(ErrorCode code, String traceId, String runId) {
        return new ErrorEvent(code.name(), code.message(), traceId, runId, code.retryable());
    }

    private static String eventName(Object event) {
        if (event instanceof FinalEvent) return "final";
        if (event instanceof SuspendEvent) return "suspend";
        if (event instanceof ErrorEvent) return "error";
        throw new IllegalArgumentException("Not a terminal event");
    }

    private static boolean valid(Map<String, Object> body) {
        if (body == null) return false;
        Object input = body.get("input");
        return input instanceof Map<?, ?> map && map.get("text") instanceof String;
    }

    public static String traceId(HttpServletRequest request) {
        String parent = request.getHeader("traceparent");
        if (parent != null) {
            String[] parts = parent.split("-");
            if (parts.length == 4 && parts[1].length() == 32) return parts[1];
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
