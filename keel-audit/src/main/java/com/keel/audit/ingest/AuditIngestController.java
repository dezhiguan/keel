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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit/events")
public class AuditIngestController {
    private final HashChainService chain;
    private final AuditMqConsumer consumer;
    private final AuditLog log;
    private final AuditCapturePolicy captures;

    public AuditIngestController(HashChainService chain, AuditMqConsumer consumer, AuditLog log, AuditCapturePolicy captures) {
        this.chain = chain;
        this.consumer = consumer;
        this.log = log;
        this.captures = captures;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestBody JsonNode event,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!event.isObject()) {
            var code = com.keel.common.error.ErrorCode.SERVER_INVALID_PARAM;
            return ResponseEntity.status(code.http()).body(Map.of("code", code.name(), "message", code.message(),
                    "trace_id", "", "retryable", code.retryable()));
        }
        boolean sync = "high".equals(event.path("risk").asText());
        JsonNode masked;
        try { masked = mask(event, authorization); }
        catch (SecurityException denied) {
            var code = com.keel.common.error.ErrorCode.AUTH_UNAUTHENTICATED;
            return ResponseEntity.status(code.http()).body(Map.of("code", code.name(), "message", code.message(),
                    "trace_id", event.path("trace_id").asText(""), "retryable", code.retryable()));
        }
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

    private JsonNode mask(JsonNode event, String authorization) {
        if (!(event instanceof ObjectNode object)) {
            return event;
        }
        ObjectNode copy = object.deepCopy();
        copy.set("payload", FieldWhitelistFilter.apply(copy.get("payload"), captures.fields(event, authorization), copy.path("agent").asText("")));
        return copy;
    }
}
