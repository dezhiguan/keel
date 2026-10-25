package com.keel.audit.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.audit.chain.HashChainService;
import com.keel.audit.store.MemoryChainStore;
import com.keel.audit.query.AuditLog;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class AuditCapturePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private AuditCapturePolicy policy() throws Exception {
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("fixture-only".getBytes(StandardCharsets.UTF_8)));
        return new AuditCapturePolicy("{\"keel-assistant\":{\"staging\":{\"captureFields\":[\"route_mode\",\"operation\"],\"tokenSha256\":\""+hash+"\"}}}");
    }
    @Test void matchingTokenPreservesOnlyManifestFields() throws Exception {
        var chain = new HashChainService(new MemoryChainStore());
        var log = new AuditLog();
        var controller = new AuditIngestController(chain, new AuditMqConsumer(chain), log, policy());
        var event = json.readTree("""
            {"event_id":"routing-fixture","ts":"2026-10-25T08:00:00Z","agent":"keel-assistant","env":"staging",
             "risk":"high","action":"invoke","decision":"allowed","payload":{"route_mode":"local_fast","operation":"get","secret":"must-drop"}}
            """);
        assertEquals(204, controller.post(event,"Bearer fixture-only").getStatusCode().value());
        var rows = log.filter("staging","keel-assistant",null,null,null,null,null,1,10);
        assertEquals(1, rows.size());
        assertEquals("local_fast", rows.getFirst().path("payload").path("route_mode").asText());
        assertFalse(rows.getFirst().path("payload").has("secret"));
        assertTrue(event.path("payload").has("secret"));
    }
    @Test void forgedAgentOrWrongEnvironmentCannotGainCapture() throws Exception {
        var event = json.readTree("{\"agent\":\"keel-assistant\",\"env\":\"staging\"}");
        assertThrows(SecurityException.class,()->policy().fields(event,"Bearer forged"));
        assertThrows(SecurityException.class,()->policy().fields(event,null));
        ((com.fasterxml.jackson.databind.node.ObjectNode)event).put("env","prod");
        assertTrue(policy().fields(event,"Bearer fixture-only").isEmpty());
        assertTrue(new AuditCapturePolicy(null).fields(event,null).isEmpty());
    }
    @Test void invalidDeploymentPolicyFailsClosed() {
        assertThrows(IllegalArgumentException.class,()->new AuditCapturePolicy("[]"));
        assertThrows(IllegalArgumentException.class,()->new AuditCapturePolicy("{\"keel-assistant\":{\"staging\":{\"captureFields\":[\"route_mode\"],\"tokenSha256\":\"bad\"}}}"));
    }
}
