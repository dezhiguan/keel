package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Guard page numbers come from the registered manifest and Langfuse guardrail observations.
 * Modes the SDK does not enforce yet are still stored on spec.guardrails so the page can round-trip them.
 */
public final class GuardrailPage {
    private static final Pattern INJECTION = Pattern.compile(
            "忽略.{0,6}(指令|规则)|ignore (all|previous)|系统提示词|system prompt|你现在是|扮演",
            Pattern.CASE_INSENSITIVE);
    private static final List<Check> CHECKS = List.of(
            new Check("input", "用户输入注入", "用户发来的内容", List.of("off", "shadow", "enforce"), "inputInjection", "shadow"),
            new Check("indirect", "工具 / 知识库内容注入", "间接注入", List.of("off", "shadow", "enforce"), "indirectInjection", "off"),
            new Check("tainted", "不可信内容后调高风险工具", "污点规则", List.of("审批", "拦截"), "taintedTool", "审批"),
            new Check("canary", "系统提示词泄露", "canary 标记", List.of("off", "enforce"), "canary", "off"),
            new Check("pii", "PII", "手机号、证件号等", List.of("off", "mask"), "pii", "mask"));

    private GuardrailPage() {}

    public static Map<String, Object> view(String agent, String version, String env, String instanceId,
                                            JsonNode manifest, List<JsonNode> observations, List<JsonNode> traceNodes,
                                            List<Caller> callers) {
        var modes = readModes(manifest);
        var known = observations != null;
        var hits = known ? count(agent, observations) : java.util.Map.<String, Integer>of();
        var checks = new ArrayList<Map<String, Object>>();
        for (var check : CHECKS) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", check.id);
            row.put("name", check.name);
            row.put("detail", check.detail);
            row.put("mode", modes.get(check.id));
            row.put("options", check.options);
            row.put("hits", known ? hits.get(check.id) : null);
            checks.add(row);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("agent", agent);
        data.put("version", version);
        data.put("env", env);
        data.put("instanceId", instanceId);
        data.put("checks", checks);
        data.put("gatewayAvailable", false);
        data.put("callers", callers.stream().map(Caller::toMap).toList());
        var tree = traceNodes == null || traceNodes.isEmpty() ? observations : traceNodes;
        data.put("traceId", traceId(agent, tree));
        data.put("trace", traceLines(agent, tree));
        return data;
    }

    public static Map<String, Object> check(JsonNode manifest, String source, String text, String instanceId, String version, long latencyMs) {
        if (!"user".equals(source) && !"tool".equals(source) && !"kb".equals(source)) {
            throw new IllegalArgumentException("来源只能是用户输入、工具返回或知识库片段");
        }
        var modes = readModes(manifest);
        var mode = "user".equals(source) ? modes.get("input") : modes.get("indirect");
        var injection = text != null && INJECTION.matcher(text).find();
        boolean hit;
        String kind;
        String tone;
        if (!injection) {
            kind = "clear";
            tone = "ok";
            hit = false;
        } else if ("off".equals(mode)) {
            kind = "off";
            tone = "warn";
            hit = true;
        } else if ("enforce".equals(mode)) {
            kind = "user".equals(source) ? "blocked" : "stripped";
            tone = "bad";
            hit = true;
        } else if ("shadow".equals(mode)) {
            kind = "shadow";
            tone = "warn";
            hit = true;
        } else {
            kind = "off";
            tone = "warn";
            hit = true;
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("source", source);
        data.put("mode", mode);
        data.put("kind", kind);
        data.put("tone", tone);
        data.put("hit", hit);
        data.put("instanceId", instanceId);
        data.put("version", version);
        data.put("latencyMs", latencyMs);
        return data;
    }

    public static void apply(ObjectNode root, JsonNode body) {
        var spec = root.with("spec");
        var guard = spec.with("guardrails");
        for (var check : CHECKS) {
            if (!body.hasNonNull(check.id)) {
                continue;
            }
            var mode = body.path(check.id).asText("");
            if (!check.options.contains(mode)) {
                throw new IllegalArgumentException(check.name + " 的模式不合法");
            }
            guard.put(check.manifestKey, store(check.id, mode));
        }
    }

    static Map<String, String> readModes(JsonNode manifest) {
        var guard = manifest == null ? null : manifest.path("spec").path("guardrails");
        var modes = new LinkedHashMap<String, String>();
        for (var check : CHECKS) {
            var raw = guard == null ? "" : guard.path(check.manifestKey).asText("");
            modes.put(check.id, display(check.id, raw, check.options, check.fallback));
        }
        return modes;
    }

    private static String store(String id, String mode) {
        if ("tainted".equals(id)) {
            return "拦截".equals(mode) ? "block" : "approve";
        }
        return mode;
    }

    private static String display(String id, String raw, List<String> options, String fallback) {
        if ("tainted".equals(id)) {
            if ("block".equals(raw) || "拦截".equals(raw)) {
                return "拦截";
            }
            if ("approve".equals(raw) || "审批".equals(raw)) {
                return "审批";
            }
            return fallback;
        }
        return options.contains(raw) ? raw : fallback;
    }

    private static Map<String, Integer> count(String agent, List<JsonNode> observations) {
        var hits = new LinkedHashMap<String, Integer>();
        for (var check : CHECKS) {
            hits.put(check.id, 0);
        }
        for (var row : observations) {
            if (!agent.equals(agentOf(row)) || !hit(row)) {
                continue;
            }
            var bucket = bucket(row);
            hits.put(bucket, hits.getOrDefault(bucket, 0) + 1);
        }
        return hits;
    }

    private static String traceId(String agent, List<JsonNode> observations) {
        JsonNode newest = null;
        for (var row : observations == null ? List.<JsonNode>of() : observations) {
            if (!agent.equals(agentOf(row))) {
                continue;
            }
            if (newest == null || start(row).compareTo(start(newest)) > 0) {
                newest = row;
            }
        }
        return newest == null ? null : newest.path("traceId").asText(null);
    }

    private static List<Map<String, String>> traceLines(String agent, List<JsonNode> observations) {
        var id = traceId(agent, observations);
        if (id == null) {
            return List.of();
        }
        var rows = new ArrayList<JsonNode>();
        for (var row : observations) {
            if (id.equals(row.path("traceId").asText())) {
                rows.add(row);
            }
        }
        rows.sort((left, right) -> start(left).compareTo(start(right)));
        var lines = new ArrayList<Map<String, String>>();
        lines.add(line("agent " + agent, ""));
        for (int i = 0; i < rows.size() && i < 12; i++) {
            var row = rows.get(i);
            var last = i == rows.size() - 1 || i == 11;
            var name = row.path("name").asText("guardrail");
            var type = observationType(row);
            var note = note(row);
            var tone = "failed".equals(status(row)) || note.contains("命中") ? "warn"
                    : note.contains("审批") ? "soft"
                    : "generation".equals(type) ? "ok" : "";
            lines.add(line((last ? "└ " : "├ ") + type + " " + name + (note.isBlank() ? "" : "    " + note), tone));
        }
        return lines;
    }

    private static Map<String, String> line(String text, String tone) {
        var row = new LinkedHashMap<String, String>();
        row.put("text", text);
        row.put("tone", tone);
        return row;
    }

    private static String bucket(JsonNode row) {
        var blob = (row.path("name").asText("") + " " + text(row.get("output"))).toLowerCase(Locale.ROOT);
        if (blob.contains("pii") || blob.contains("脱敏")) {
            return "pii";
        }
        if (blob.contains("canary") || blob.contains("泄露")) {
            return "canary";
        }
        if (blob.contains("taint") || blob.contains("污点")) {
            return "tainted";
        }
        if (blob.contains("tool") || blob.contains("retriever") || blob.contains("间接") || blob.contains("知识库")) {
            return "indirect";
        }
        return "input";
    }

    static boolean hit(JsonNode row) {
        if ("failed".equals(status(row))) {
            return true;
        }
        var output = text(row.get("output"));
        return output.contains("命中");
    }

    private static String status(JsonNode row) {
        var meta = row.path("metadata");
        var direct = meta.path("keel.status").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        return meta.path("attributes").path("keel.status").asText("");
    }

    static String agentOf(JsonNode row) {
        var meta = row.path("metadata");
        var direct = meta.path("keel.agent").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        return meta.path("attributes").path("keel.agent").asText("");
    }

    private static String observationType(JsonNode row) {
        var named = row.path("metadata").path("langfuse.observation.type").asText("");
        if (named.isBlank()) {
            named = row.path("metadata").path("attributes").path("langfuse.observation.type").asText("");
        }
        if (!named.isBlank()) {
            return named.toLowerCase(Locale.ROOT);
        }
        var type = row.path("type").asText("span");
        return "GUARDRAIL".equalsIgnoreCase(type) ? "guardrail" : type.toLowerCase(Locale.ROOT);
    }

    private static String note(JsonNode row) {
        var output = text(row.get("output")).replace('\n', ' ').trim();
        if (output.length() > 80) {
            output = output.substring(0, 80) + "…";
        }
        if (!output.isBlank()) {
            return output;
        }
        return "failed".equals(status(row)) ? "失败" : "";
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        var nested = node.path("text").asText("");
        return nested.isBlank() ? node.toString() : nested;
    }

    private static String start(JsonNode row) {
        return row.path("startTime").asText("");
    }

    public record Caller(String name, String key, String layer) {
        Map<String, String> toMap() {
            return Map.of("name", name, "key", key, "layer", layer);
        }
    }

    private record Check(String id, String name, String detail, List<String> options, String manifestKey, String fallback) {}
}
