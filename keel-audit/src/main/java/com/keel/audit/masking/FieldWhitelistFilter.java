package com.keel.audit.masking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/** Drops every payload key that is not in captureFields, then masks PII in the values that remain. */
public final class FieldWhitelistFilter {
    private static final Logger log = LoggerFactory.getLogger(FieldWhitelistFilter.class);

    private FieldWhitelistFilter() {}

    public static ObjectNode apply(JsonNode payload, Set<String> captureFields, String agent) {
        var kept = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (captureFields == null) {
            log.warn("trace_id={} agent={} 缺少 captureFields，审计 payload 置空", org.slf4j.MDC.get("trace_id"), agent);
            return kept;
        }
        if (payload != null && payload.isObject()) {
            payload.fields().forEachRemaining(entry -> {
                if (captureFields.contains(entry.getKey())) {
                    kept.set(entry.getKey(), maskValue(entry.getValue()));
                }
            });
        }
        return kept;
    }

    private static JsonNode maskValue(JsonNode value) {
        if (value != null && value.isTextual()) {
            return TextNode.valueOf(PiiMasker.mask(value.asText()));
        }
        return value;
    }
}
