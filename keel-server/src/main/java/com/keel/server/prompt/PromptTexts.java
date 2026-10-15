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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hash, diff and config rules shared by save, sync and the console diff endpoint. */
public final class PromptTexts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");
    private static final Set<String> CONFIG_KEYS = Set.of("temperature", "max_tokens", "top_p");
    private static final Set<String> SECTION_KINDS = Set.of("role", "task", "constraint", "tool", "output", "example");
    private static final Set<String> KEEL_KEYS = Set.of("sections", "variables", "outputContract", "samples");
    private static final Pattern SECRET = Pattern.compile("(?i)sk-[A-Za-z0-9]{8,}|1[3-9]\\d{9}");

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
            if ("keel".equals(entry.getKey())) {
                out.set("keel", structure(entry.getValue()));
                return;
            }
            if (!CONFIG_KEYS.contains(entry.getKey())) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "config 只认 temperature、max_tokens、top_p 和 keel");
            }
            out.set(entry.getKey(), entry.getValue());
        });
        return out;
    }

    public static JsonNode compileSections(JsonNode sections, String type, JsonNode prompt) {
        var text = renderSections(sections);
        if ("chat".equals(type)) {
            var messages = JSON.createArrayNode();
            var system = JSON.createObjectNode();
            system.put("role", "system");
            system.put("content", text);
            messages.add(system);
            var kept = false;
            if (prompt != null && prompt.isArray()) {
                for (var message : prompt) {
                    if ("system".equals(message.path("role").asText(""))) {
                        continue;
                    }
                    var row = JSON.createObjectNode();
                    var role = message.path("role").asText("");
                    row.put("role", role.isBlank() ? "user" : role);
                    row.put("content", message.path("content").asText(""));
                    messages.add(row);
                    kept = true;
                }
            }
            if (!kept) {
                var user = JSON.createObjectNode();
                user.put("role", "user");
                user.put("content", "{{question}}");
                messages.add(user);
            }
            return messages;
        }
        return JSON.getNodeFactory().textNode(text);
    }

    public static List<Map<String, String>> removedConstraints(JsonNode previousConfig, JsonNode nextConfig) {
        var after = new java.util.LinkedHashSet<String>();
        for (var item : constraintsOf(nextConfig)) {
            after.add(item.get("text"));
        }
        var removed = new ArrayList<Map<String, String>>();
        for (var item : constraintsOf(previousConfig)) {
            if (!after.contains(item.get("text"))) {
                removed.add(item);
            }
        }
        return removed;
    }

    public static Object applyVariables(JsonNode prompt, String type, JsonNode structure, JsonNode variables) {
        var missing = missingVariables(prompt, type, structure, variables);
        if (!missing.isEmpty()) {
            throw new KeelException(ErrorCode.PROMPT_VARIABLE_MISSING, "缺少变量 " + String.join("、", missing));
        }
        if ("chat".equals(type)) {
            var rows = new ArrayList<Map<String, String>>();
            if (prompt != null && prompt.isArray()) {
                for (var message : prompt) {
                    var row = new LinkedHashMap<String, String>();
                    row.put("role", message.path("role").asText("user"));
                    row.put("content", fill(message.path("content").asText(""), variables));
                    rows.add(row);
                }
            }
            return rows;
        }
        var text = prompt == null || prompt.isNull() ? "" : prompt.asText("");
        return fill(text, variables);
    }

    public static List<Map<String, Object>> checks(JsonNode prompt, String type, JsonNode structure) {
        var rows = new ArrayList<Map<String, Object>>();
        var declared = new java.util.LinkedHashSet<String>();
        if (structure != null && structure.isObject()) {
            structure.path("variables").forEach(item -> {
                var name = item.path("name").asText("");
                if (!name.isBlank()) {
                    declared.add(name);
                }
            });
        }
        var used = variables(prompt, type);
        var undeclared = new ArrayList<String>();
        for (var name : used) {
            if (!declared.contains(name)) {
                undeclared.add(name);
            }
        }
        if (used.isEmpty() || undeclared.isEmpty()) {
            rows.add(check(true, "变量都已声明"));
        } else {
            rows.add(check(false, "变量未声明：" + String.join("、", undeclared)));
        }
        var secret = SECRET.matcher(plain(prompt, type)).find();
        rows.add(check(!secret, secret ? "正文里有疑似密钥或手机号" : "没有疑似密钥或手机号"));
        var contract = structure != null && structure.has("outputContract") && structure.get("outputContract").isObject();
        rows.add(check(contract, contract ? "输出契约已声明" : "未声明输出契约"));
        return rows;
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

    private static ObjectNode structure(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "keel 必须是对象");
        }
        var out = JSON.createObjectNode();
        raw.fields().forEachRemaining(entry -> {
            switch (entry.getKey()) {
                case "sections" -> out.set("sections", sections(entry.getValue()));
                case "variables" -> out.set("variables", variableSchema(entry.getValue()));
                case "outputContract" -> {
                    if (!entry.getValue().isObject()) {
                        throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "输出契约必须是对象");
                    }
                    out.set("outputContract", entry.getValue());
                }
                case "samples" -> out.set("samples", samples(entry.getValue()));
                default -> {
                    if (!KEEL_KEYS.contains(entry.getKey())) {
                        throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "keel 只认 sections、variables、outputContract、samples");
                    }
                }
            }
        });
        return out;
    }

    private static ArrayNode sections(JsonNode raw) {
        if (!raw.isArray()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "sections 必须是数组");
        }
        var out = JSON.createArrayNode();
        var constraint = 0;
        for (var item : raw) {
            if (!item.isObject()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "分段必须是对象");
            }
            var kind = item.path("kind").asText("");
            if (!SECTION_KINDS.contains(kind)) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "分段类型无效");
            }
            var text = item.path("text").asText("").trim();
            if (text.isEmpty()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "分段正文不能为空");
            }
            var row = JSON.createObjectNode();
            row.put("kind", kind);
            row.put("text", text);
            if ("constraint".equals(kind)) {
                constraint++;
                var dimension = item.path("dimension").asText("").trim();
                if (dimension.isEmpty()) {
                    throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "约束必须写评测维度");
                }
                var id = item.path("id").asText("").trim();
                row.put("id", id.isEmpty() ? "c-" + constraint : id);
                row.put("dimension", dimension);
            }
            out.add(row);
        }
        return out;
    }

    private static ArrayNode variableSchema(JsonNode raw) {
        if (!raw.isArray()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "variables 必须是数组");
        }
        var out = JSON.createArrayNode();
        var seen = new ArrayList<String>();
        for (var item : raw) {
            if (!item.isObject()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "变量 schema 必须是对象");
            }
            var name = item.path("name").asText("").trim();
            if (!name.matches("\\w+")) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "变量名无效");
            }
            if (seen.contains(name)) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "变量重复");
            }
            seen.add(name);
            var row = JSON.createObjectNode();
            row.put("name", name);
            var type = item.path("type").asText("").trim();
            row.put("type", type.isEmpty() ? "string" : type);
            row.put("source", item.path("source").asText(""));
            row.put("required", !item.has("required") || item.get("required").asBoolean(true));
            row.put("limit", item.path("limit").asText(""));
            row.put("pii", item.path("pii").asBoolean(false));
            out.add(row);
        }
        return out;
    }

    private static ArrayNode samples(JsonNode raw) {
        if (!raw.isArray()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "samples 必须是数组");
        }
        var out = JSON.createArrayNode();
        for (var item : raw) {
            if (!item.isObject()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "样例必须是对象");
            }
            var name = item.path("name").asText("").trim();
            if (name.isEmpty()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "样例要有名字");
            }
            var vars = item.get("variables");
            if (vars != null && !vars.isNull() && !vars.isObject()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "样例变量必须是对象");
            }
            var row = JSON.createObjectNode();
            row.put("name", name);
            var copy = JSON.createObjectNode();
            if (vars != null && vars.isObject()) {
                vars.fields().forEachRemaining(entry -> copy.put(entry.getKey(), entry.getValue().asText("")));
            }
            row.set("variables", copy);
            out.add(row);
        }
        return out;
    }

    private static String renderSections(JsonNode sections) {
        var groups = new LinkedHashMap<String, List<String>>();
        for (var section : sections) {
            groups.computeIfAbsent(section.path("kind").asText(), key -> new ArrayList<>()).add(section.path("text").asText());
        }
        var blocks = new ArrayList<String>();
        addBlock(blocks, groups.get("role"), null);
        addBlock(blocks, groups.get("task"), null);
        addBlock(blocks, groups.get("constraint"), "约束：");
        addBlock(blocks, groups.get("tool"), "工具：");
        addBlock(blocks, groups.get("output"), "输出：");
        addBlock(blocks, groups.get("example"), "示例：");
        return String.join("\n\n", blocks);
    }

    private static void addBlock(List<String> blocks, List<String> lines, String title) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        if (title == null) {
            blocks.add(String.join("\n", lines));
            return;
        }
        if ("约束：".equals(title)) {
            var body = new ArrayList<String>();
            for (var line : lines) {
                body.add("- " + line);
            }
            blocks.add(title + "\n" + String.join("\n", body));
            return;
        }
        blocks.add(title + "\n" + String.join("\n", lines));
    }

    private static List<Map<String, String>> constraintsOf(JsonNode config) {
        var found = new ArrayList<Map<String, String>>();
        var keel = config == null ? null : config.get("keel");
        if (keel == null || !keel.isObject()) {
            return found;
        }
        for (var section : keel.path("sections")) {
            if (!section.isObject() || !"constraint".equals(section.path("kind").asText())) {
                continue;
            }
            var text = section.path("text").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            var row = new LinkedHashMap<String, String>();
            row.put("id", section.path("id").asText(""));
            row.put("text", text);
            row.put("dimension", section.path("dimension").asText(""));
            found.add(row);
        }
        return found;
    }

    private static List<String> missingVariables(JsonNode prompt, String type, JsonNode structure, JsonNode variables) {
        var missing = new ArrayList<String>();
        for (var name : variables(prompt, type)) {
            if (!present(variables, name)) {
                missing.add(name);
            }
        }
        if (structure != null && structure.isObject()) {
            for (var item : structure.path("variables")) {
                if (!item.isObject() || !item.path("required").asBoolean(true)) {
                    continue;
                }
                var name = item.path("name").asText("");
                if (!name.isBlank() && !present(variables, name) && !missing.contains(name)) {
                    missing.add(name);
                }
            }
        }
        return missing;
    }

    private static boolean present(JsonNode variables, String name) {
        return variables != null && variables.isObject() && variables.has(name) && !variables.get(name).isNull();
    }

    private static String fill(String text, JsonNode variables) {
        var matcher = VARIABLE.matcher(text);
        var out = new StringBuilder();
        while (matcher.find()) {
            var value = variables.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value.asText("")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<String, Object> check(boolean ok, String text) {
        var row = new LinkedHashMap<String, Object>();
        row.put("ok", ok);
        row.put("text", text);
        return row;
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
