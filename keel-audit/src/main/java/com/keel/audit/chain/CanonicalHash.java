package com.keel.audit.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** contracts/tests/README.md canonical audit JSON. Hash field is excluded. */
public final class CanonicalHash {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CanonicalHash() {}

    public static String sha256(JsonNode event) {
        String prev = event.path("prev_hash").isMissingNode() || event.path("prev_hash").isNull()
                ? "" : event.path("prev_hash").asText("");
        return sha256(canonical(event), prev);
    }

    public static String sha256(String canonicalJson, String prevHash) {
        String prefix = prevHash == null ? "" : prevHash;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((prefix + canonicalJson).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(JsonNode event) {
        StringBuilder out = new StringBuilder();
        append(event, out, "", true);
        return out.toString();
    }

    private static void append(JsonNode value, StringBuilder out, String path, boolean root) {
        if (value.isFloatingPointNumber()) {
            throw new IllegalArgumentException(path.isEmpty() ? "/" : path);
        }
        if (value.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!entry.getValue().isNull() && !(root && "hash".equals(entry.getKey()))) {
                    entries.add(entry);
                }
            }
            entries.sort((left, right) -> java.util.Arrays.compareUnsigned(
                    left.getKey().getBytes(StandardCharsets.UTF_8),
                    right.getKey().getBytes(StandardCharsets.UTF_8)));
            out.append('{');
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                Map.Entry<String, JsonNode> entry = entries.get(index);
                out.append(write(entry.getKey())).append(':');
                append(entry.getValue(), out, path + "/" + escape(entry.getKey()), false);
            }
            out.append('}');
            return;
        }
        if (value.isArray()) {
            out.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                append(value.get(index), out, path + "/" + index, false);
            }
            out.append(']');
            return;
        }
        if ("/ts".equals(path) && value.isTextual() && value.asText().endsWith("Z") && value.asText().contains(".")) {
            String text = value.asText();
            int dot = text.indexOf('.');
            String fraction = text.substring(dot + 1, text.length() - 1).replaceFirst("0+$", "");
            String normalized = text.substring(0, dot) + (fraction.isEmpty() ? "" : "." + fraction) + "Z";
            out.append(write(normalized));
            return;
        }
        out.append(write(value));
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
