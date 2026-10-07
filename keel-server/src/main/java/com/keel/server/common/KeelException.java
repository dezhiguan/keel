package com.keel.server.common;

import com.keel.common.error.ErrorCode;

import java.util.Map;

public class KeelException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public KeelException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public KeelException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode code() { return code; }

    public Map<String, Object> details() { return details; }
}
