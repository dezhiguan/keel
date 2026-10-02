package com.keel.server.common;

/** Success envelope of contracts/console-api.openapi.yaml#/components/schemas/Envelope. */
public record R<T>(String code, String message, String traceId, T data) {
    private static final String OK = "OK";

    public static <T> R<T> ok(T data) {
        return new R<>(OK, null, TraceIds.current(), data);
    }
}
