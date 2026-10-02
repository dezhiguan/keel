package com.keel.common.error;

/**
 * Mirrors contracts/error-codes.yaml; names, retryable and http must stay identical to the yaml.
 * TODO(P0-2): replace with the enum generated from contracts/error-codes.yaml.
 */
public enum ErrorCode {
    SERVER_INVALID_PARAM("请求参数不合法", false, 400),
    SERVER_NOT_FOUND("资源不存在", false, 404),
    SERVER_INTERNAL_ERROR("服务内部错误", true, 500);

    private final String message;
    private final boolean retryable;
    private final int http;

    ErrorCode(String message, boolean retryable, int http) {
        this.message = message;
        this.retryable = retryable;
        this.http = http;
    }

    public String message() { return message; }
    public boolean retryable() { return retryable; }
    public int http() { return http; }
}
