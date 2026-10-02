package com.keel.starter.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keel.common.model.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Test implementation of contracts/tests/README.md. */
final class CanonicalAudit {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private CanonicalAudit() {}

    static String sha256(AuditEvent event) throws JsonProcessingException, NoSuchAlgorithmException {
        JsonNode value = MAPPER.valueToTree(event);
        StringBuilder out = new StringBuilder();
        append(value, out, "", true);
        String prefix = event.prev_hash() == null ? "" : event.prev_hash();
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest((prefix + out).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }

    private static void append(JsonNode value, StringBuilder out, String path, boolean root)
            throws JsonProcessingException {
        if (value.isFloatingPointNumber()) throw new IllegalArgumentException(path);
        if (value.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!entry.getValue().isNull() && !(root && entry.getKey().equals("hash"))) entries.add(entry);
            }
            entries.sort((a, b) -> java.util.Arrays.compareUnsigned(
                    a.getKey().getBytes(StandardCharsets.UTF_8), b.getKey().getBytes(StandardCharsets.UTF_8)));
            out.append('{');
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) out.append(',');
                Map.Entry<String, JsonNode> entry = entries.get(index);
                out.append(MAPPER.writeValueAsString(entry.getKey())).append(':');
                append(entry.getValue(), out, path + "/" + escape(entry.getKey()), false);
            }
            out.append('}');
        } else if (value.isArray()) {
            out.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) out.append(',');
                append(value.get(index), out, path + "/" + index, false);
            }
            out.append(']');
        } else if (path.equals("/ts") && value.isTextual() && value.asText().endsWith("Z")
                && value.asText().contains(".")) {
            String text = value.asText();
            int dot = text.indexOf('.');
            String fraction = text.substring(dot + 1, text.length() - 1).replaceFirst("0+$", "");
            String normalized = text.substring(0, dot) + (fraction.isEmpty() ? "" : "." + fraction) + "Z";
            out.append(MAPPER.writeValueAsString(normalized));
        } else {
            out.append(MAPPER.writeValueAsString(value));
        }
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
