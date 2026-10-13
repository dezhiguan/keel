package com.keel.server.integration.langfuse;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Surfaces Langfuse 429 with Retry-After.
 * This advice has to run first. The generic handler would otherwise report an internal error.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class LangfuseExceptionHandler {
    @ExceptionHandler(LangfuseRateLimit.class)
    ResponseEntity<ApiError> limited(LangfuseRateLimit error) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(error.retryAfterSeconds()))
                .body(ApiError.of(ErrorCode.INSIGHT_UPSTREAM_UNAVAILABLE, ErrorCode.INSIGHT_UPSTREAM_UNAVAILABLE.message()));
    }
}
