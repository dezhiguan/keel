package com.keel.starter.tracing;

/** MDC and header names carried over from careermate observability. */
public final class MdcKeys {
    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String SESSION_ID = "sessionId";
    /** Careermate log pattern and ApiResponse read this camelCase key. */
    public static final String TRACE_ID = "traceId";
    /** Keel log rule. Same value as {@link #TRACE_ID}. */
    public static final String TRACE_ID_SNAKE = "trace_id";
    /** Keel log rule. Careermate had no agent key; service name stays in {@link #SERVICE}. */
    public static final String AGENT = "agent";
    public static final String SPAN_ID = "spanId";
    public static final String SERVICE = "service";

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_SESSION_ID = "X-CareerMate-Session-Id";

    private MdcKeys() {}
}
