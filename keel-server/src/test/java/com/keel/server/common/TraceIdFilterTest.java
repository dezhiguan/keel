package com.keel.server.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceIdFilterTest {
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test void takesTraceIdFromValidTraceparent() {
        assertEquals(TRACE_ID, TraceIdFilter.traceIdOf("00-" + TRACE_ID + "-00f067aa0ba902b7-01"));
    }

    @Test void generatesTraceIdWhenHeaderMissingOrMalformed() {
        for (var header : new String[] {null, "", "garbage", "00-" + TRACE_ID + "-short-01"}) {
            var id = TraceIdFilter.traceIdOf(header);
            assertTrue(id.matches("[0-9a-f]{32}"), id);
            assertNotEquals(TRACE_ID, id);
        }
    }
}
