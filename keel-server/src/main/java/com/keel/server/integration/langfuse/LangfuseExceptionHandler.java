package com.keel.server.integration.langfuse;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.ApiError;
import com.keel.server.common.TraceIds;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Surfaces Langfuse 429 with the same Retry-After, instead of a generic 500. */
@RestControllerAdvice
public class LangfuseExceptionHandler {
    @ExceptionHandler(LangfuseRateLimit.class)
    ResponseEntity<ApiError> limited(LangfuseRateLimit error) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(error.retryAfterSeconds()))
                .body(new ApiError(ErrorCode.SERVER_INTERNAL_ERROR.name(), error.getMessage(), TraceIds.current(), true));
    }
}
