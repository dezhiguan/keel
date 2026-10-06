package com.keel.server.integration.langfuse;

/** Langfuse answered 429. {@code retryAfterSeconds} is the Retry-After value, or 60 when the header is absent. */
public final class LangfuseRateLimit extends RuntimeException {
    private final int retryAfterSeconds;

    public LangfuseRateLimit(int retryAfterSeconds) {
        super("Langfuse 读接口限流");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
