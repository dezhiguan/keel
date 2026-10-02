package com.keel.server.common;

import com.keel.common.error.ErrorCode;

public class KeelException extends RuntimeException {
    private final ErrorCode code;

    public KeelException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
