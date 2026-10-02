package com.keel.common.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keel.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class GeneratedModelsTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    @Test
    void acceptsUnknownFieldsAndPreservesMissingOptionalValues() throws Exception {
        AgentManifest manifest = mapper.readValue("""
            {"apiVersion":"keel/v1","metadata":{"name":"demo-agent","future":7},
             "spec":{"runtime":{"endpoint":"https://example.test"},"future":true},"future":1}
            """, AgentManifest.class);
        assertEquals("demo-agent", manifest.metadata().name());
        assertNull(manifest.spec().audit());
        assertNull(manifest.spec().runtime().liveness());
        assertFalse(mapper.writeValueAsString(manifest).contains("future"));
    }

    @Test
    void dottedEnumRoundTripsAndErrorRetryabilityComesFromContract() throws Exception {
        AuditEvent.AuditEventActionValue action = mapper.readValue("\"tool.call\"",
                AuditEvent.AuditEventActionValue.class);
        assertEquals("\"tool.call\"", mapper.writeValueAsString(action));
        assertTrue(ErrorCode.GW_QUOTA_EXCEEDED.retryable());
        assertEquals(429, ErrorCode.GW_QUOTA_EXCEEDED.http());
    }

    @Test
    void auditTimestampAndCanonicalJsonMatchPythonFixture() throws Exception {
        AuditEvent event = mapper.readValue("""
            {"event_id":"e1","ts":"2026-09-29T12:49:30Z","agent":"agent-a",
             "env":"prod","action":"tool.call","risk":"low","decision":"allowed",
             "hash":"0000000000000000000000000000000000000000000000000000000000000000",
             "future":123}
            """, AuditEvent.class);
        assertEquals("2026-09-29T12:49:30Z", event.ts().toString());
        assertEquals("{\"action\":\"tool.call\",\"agent\":\"agent-a\",\"decision\":\"allowed\",\"env\":\"prod\",\"event_id\":\"e1\",\"hash\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"risk\":\"low\",\"ts\":\"2026-09-29T12:49:30Z\"}",
                mapper.writeValueAsString(event));
    }
}
