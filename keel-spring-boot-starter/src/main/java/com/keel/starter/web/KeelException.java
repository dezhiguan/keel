package com.keel.starter.web;

import com.keel.common.error.ErrorCode;

/** Carries a generated {@link ErrorCode}. Callers must not invent code strings. */
public final class KeelException extends RuntimeException {
    private final ErrorCode code;

    public KeelException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
