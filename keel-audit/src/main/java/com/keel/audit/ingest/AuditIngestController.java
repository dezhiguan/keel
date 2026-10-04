package com.keel.audit.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.audit.chain.HashChainService;
import com.keel.audit.masking.FieldWhitelistFilter;
import com.keel.audit.query.AuditLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit/events")
public class AuditIngestController {
    private final HashChainService chain;
    private final AuditMqConsumer consumer;
    private final AuditLog log;

    public AuditIngestController(HashChainService chain, AuditMqConsumer consumer, AuditLog log) {
        this.chain = chain;
        this.consumer = consumer;
        this.log = log;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestBody JsonNode event) {
        boolean sync = "high".equals(event.path("risk").asText());
        JsonNode masked = mask(event);
        try {
            if (sync) {
                chain.append(masked, true);
            } else {
                consumer.accept(masked);
            }
            log.add(masked);
            return ResponseEntity.noContent().build();
        } catch (HashChainService.AuditAppendException error) {
            var code = error.code();
            return ResponseEntity.status(code.http()).body(Map.of(
                    "code", code.name(),
                    "message", code.message(),
                    "trace_id", event.path("trace_id").asText(""),
                    "retryable", code.retryable()));
        }
    }

    private static JsonNode mask(JsonNode event) {
        if (!(event instanceof ObjectNode object)) {
            return event;
        }
        ObjectNode copy = object.deepCopy();
        copy.set("payload", FieldWhitelistFilter.apply(copy.get("payload"), null, copy.path("agent").asText("")));
        return copy;
    }
}
