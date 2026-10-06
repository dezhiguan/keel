package com.keel.server.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Hash, diff and config rules shared by save, sync and the console diff endpoint. */
public final class PromptTexts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");
    private static final Set<String> CONFIG_KEYS = Set.of("temperature", "max_tokens", "top_p");

    private PromptTexts() {}

    public static String sha256(JsonNode prompt, String type) {
        return "sha256:" + HexFormat.of().formatHex(digest(canonical(prompt, type)));
    }

    public static boolean sameHash(String provided, JsonNode prompt, String type) {
        if (provided == null || provided.isBlank()) {
            return false;
        }
        var expected = sha256(prompt, type);
        var given = provided.startsWith("sha256:") ? provided : "sha256:" + provided;
        return expected.equalsIgnoreCase(given);
    }

    public static List<String> variables(JsonNode prompt, String type) {
        var found = new ArrayList<String>();
        var matcher = VARIABLE.matcher(plain(prompt, type));
        while (matcher.find()) {
            if (!found.contains(matcher.group(1))) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    public static ObjectNode config(JsonNode raw) {
        var out = JSON.createObjectNode();
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return out;
        }
        if (!raw.isObject()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "config 必须是对象");
        }
        if (raw.has("model")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "config 不能写 model，模型由 manifest 决定");
        }
        raw.fields().forEachRemaining(entry -> {
            if (!CONFIG_KEYS.contains(entry.getKey())) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "config 只认 temperature、max_tokens、top_p");
            }
            out.set(entry.getKey(), entry.getValue());
        });
        return out;
    }

    public static List<String> lines(JsonNode prompt, String type) {
        var lines = new ArrayList<String>();
        if ("chat".equals(type)) {
            if (prompt != null && prompt.isArray()) {
                prompt.forEach(message -> {
                    lines.add("[" + message.path("role").asText("user") + "]");
                    for (var line : message.path("content").asText("").split("\n", -1)) {
                        lines.add(line);
                    }
                });
            }
            return lines;
        }
        var text = prompt == null || prompt.isNull() ? "" : prompt.asText("");
        for (var line : text.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }

    public static Map<String, Object> diff(JsonNode from, String fromType, JsonNode to, String toType) {
        var left = lines(from, fromType);
        var right = lines(to, toType);
        int n = left.size();
        int m = right.size();
        var score = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                score[i][j] = left.get(i).equals(right.get(j))
                        ? score[i + 1][j + 1] + 1
                        : Math.max(score[i + 1][j], score[i][j + 1]);
            }
        }
        var rows = new ArrayList<Map<String, Object>>();
        int added = 0;
        int removed = 0;
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (left.get(i).equals(right.get(j))) {
                rows.add(line("same", left.get(i)));
                i++;
                j++;
            } else if (score[i + 1][j] >= score[i][j + 1]) {
                rows.add(line("del", left.get(i)));
                removed++;
                i++;
            } else {
                rows.add(line("add", right.get(j)));
                added++;
                j++;
            }
        }
        while (i < n) {
            rows.add(line("del", left.get(i)));
            removed++;
            i++;
        }
        while (j < m) {
            rows.add(line("add", right.get(j)));
            added++;
            j++;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("lines", rows);
        body.put("added", added);
        body.put("removed", removed);
        return body;
    }

    public static JsonNode asPrompt(JsonNode prompt, String type) {
        if ("chat".equals(type)) {
            if (prompt == null || !prompt.isArray() || prompt.isEmpty()) {
                throw new KeelException(ErrorCode.PROMPT_TYPE_MISMATCH, ErrorCode.PROMPT_TYPE_MISMATCH.message());
            }
            var messages = JSON.createArrayNode();
            prompt.forEach(message -> {
                if (!message.isObject() || (message.path("role").asText("").isBlank() && message.path("content").asText("").isBlank())) {
                    throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "chat 提示词的每条消息都要有 role 和 content");
                }
                var row = JSON.createObjectNode();
                row.put("role", message.path("role").asText("user"));
                row.put("content", message.path("content").asText(""));
                messages.add(row);
            });
            return messages;
        }
        if (prompt == null || prompt.isArray() || !prompt.isTextual()) {
            throw new KeelException(ErrorCode.PROMPT_TYPE_MISMATCH, ErrorCode.PROMPT_TYPE_MISMATCH.message());
        }
        return JSON.getNodeFactory().textNode(prompt.asText());
    }

    private static Map<String, Object> line(String op, String text) {
        var row = new LinkedHashMap<String, Object>();
        row.put("op", op);
        row.put("text", text);
        return row;
    }

    private static String canonical(JsonNode prompt, String type) {
        if ("chat".equals(type)) {
            ArrayNode messages = JSON.createArrayNode();
            if (prompt != null && prompt.isArray()) {
                prompt.forEach(message -> {
                    ObjectNode row = JSON.createObjectNode();
                    row.put("role", message.path("role").asText(""));
                    row.put("content", message.path("content").asText(""));
                    messages.add(row);
                });
            }
            try {
                return JSON.writeValueAsString(messages);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return prompt == null || prompt.isNull() ? "" : prompt.asText("");
    }

    private static String plain(JsonNode prompt, String type) {
        if ("chat".equals(type)) {
            var text = new StringBuilder();
            if (prompt != null && prompt.isArray()) {
                prompt.forEach(message -> text.append(message.path("content").asText("")).append('\n'));
            }
            return text.toString();
        }
        return prompt == null || prompt.isNull() ? "" : prompt.asText("");
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
