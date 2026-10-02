package com.keel.server.common;

import com.keel.common.error.ErrorCode;

/** Error body of contracts/console-api.openapi.yaml#/components/schemas/ApiError. */
public record ApiError(String code, String message, String traceId, boolean retryable) {
    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message, TraceIds.current(), code.retryable());
    }
}
