package com.keel.server.common;

import com.keel.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(KeelException.class)
    ResponseEntity<?> keel(KeelException e) {
        if (e.details().isEmpty()) return body(e.code(), e.getMessage());
        ApiError error = ApiError.of(e.code(), e.getMessage());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("code", error.code());
        payload.put("message", error.message());
        payload.put("traceId", error.traceId());
        payload.put("retryable", error.retryable());
        payload.put("details", e.details());
        return ResponseEntity.status(e.code().http()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(payload);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalidParam(Exception e) {
        return body(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        return body(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    ResponseEntity<?> streamTimeout(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        if (response.getContentType() != null && response.getContentType().startsWith("text/event-stream")) {
            var error = ApiError.of(ErrorCode.SERVER_INTERNAL_ERROR, "智能体调用超时，请稍后重试");
            try {
                var payload = java.util.Map.of("code", error.code(), "message", error.message(),
                        "trace_id", error.traceId() == null ? TraceIdFilter.traceIdOf(request.getHeader("traceparent")) : error.traceId(), "retryable", error.retryable(), "run_id", "");
                var frame = "event: error\ndata: " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload) + "\n\n";
                return ResponseEntity.ok().contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM).body(frame);
            } catch (com.fasterxml.jackson.core.JsonProcessingException unexpected) {
                throw new IllegalStateException(unexpected);
            }
        }
        return body(ErrorCode.SERVER_INTERNAL_ERROR, "请求超时，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception e) {
        log.error("unhandled exception", e);
        return body(ErrorCode.SERVER_INTERNAL_ERROR, ErrorCode.SERVER_INTERNAL_ERROR.message());
    }

    private static ResponseEntity<ApiError> body(ErrorCode code, String message) {
        return ResponseEntity.status(code.http()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiError.of(code, message));
    }
}
