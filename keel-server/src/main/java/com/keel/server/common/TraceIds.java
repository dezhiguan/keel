package com.keel.server.common;

import org.slf4j.MDC;

public final class TraceIds {
    public static final String MDC_KEY = "trace_id";

    private TraceIds() {}

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
