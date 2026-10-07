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
        return ResponseEntity.status(e.code().http()).body(payload);
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

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception e) {
        log.error("unhandled exception", e);
        return body(ErrorCode.SERVER_INTERNAL_ERROR, ErrorCode.SERVER_INTERNAL_ERROR.message());
    }

    private static ResponseEntity<ApiError> body(ErrorCode code, String message) {
        return ResponseEntity.status(code.http()).body(ApiError.of(code, message));
    }
}
