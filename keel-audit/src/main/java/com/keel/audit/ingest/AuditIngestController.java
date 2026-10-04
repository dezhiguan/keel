package com.keel.audit.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.audit.chain.HashChainService;
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

    public AuditIngestController(HashChainService chain, AuditMqConsumer consumer) {
        this.chain = chain;
        this.consumer = consumer;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestBody JsonNode event) {
        boolean sync = "high".equals(event.path("risk").asText());
        try {
            if (sync) {
                chain.append(event, true);
            } else {
                consumer.accept(event);
            }
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
}
