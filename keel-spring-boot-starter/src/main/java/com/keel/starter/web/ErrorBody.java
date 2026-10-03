package com.keel.starter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keel.common.error.ErrorCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, String trace_id, String run_id, boolean retryable) {
    public static ErrorBody of(ErrorCode code, String traceId, String runId) {
        return new ErrorBody(code.name(), code.message(), traceId, runId, code.retryable());
    }
}
