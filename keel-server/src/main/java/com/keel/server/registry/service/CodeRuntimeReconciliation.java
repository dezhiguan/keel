package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit administrator reconciliation of a stale registration with its registered runtime. */
final class CodeRuntimeReconciliation {
    private CodeRuntimeReconciliation() {}

    static JsonNode align(JsonNode prior, JsonNode candidate, JsonNode actual, Set<String> unavailableTools) {
        if (!prior.path("metadata").path("name").equals(actual.path("metadata").path("name")))
            throw invalid("运行清单身份不一致");
        var proposed = candidate.path("spec");
        var live = actual.path("spec");
        for (var field : List.of("tools", "audit")) {
            if (!normalized(proposed.path(field)).equals(normalized(live.path(field))))
                throw invalid("仅可对齐运行端已有的 " + field);
        }
        if (!normalized(proposed.path("eval").path("gate")).equals(normalized(live.path("eval").path("gate"))))
            throw invalid("门禁须与运行端一致");
        var oldGate = prior.path("spec").path("eval").path("gate");
        var nextGate = proposed.path("eval").path("gate");
        requireGate(oldGate, nextGate);
        var oldTools = tools(prior.path("spec").path("tools"));
        var nextTools = tools(proposed.path("tools"));
        for (var entry : oldTools.entrySet()) {
            if (!entry.getValue().equals(nextTools.get(entry.getKey()))) throw invalid("已有工具权限不可变化");
        }
        for (var entry : nextTools.entrySet()) {
            if (!oldTools.containsKey(entry.getKey()) && (unavailableTools.contains(entry.getKey())
                    || entry.getValue().path("shared").asBoolean(false)))
                throw invalid("共享或其他智能体的工具须走授权流程");
        }
        var fields = new HashSet<String>();
        proposed.path("audit").path("captureFields").forEach(field -> fields.add(field.asText()));
        for (var field : prior.path("spec").path("audit").path("captureFields")) {
            if (!fields.contains(field.asText())) throw invalid("不可移除已有审计字段");
        }
        ObjectNode aligned = prior.deepCopy();
        aligned.withObject("/spec").set("tools", proposed.path("tools").deepCopy());
        aligned.withObject("/spec").set("audit", proposed.path("audit").deepCopy());
        aligned.withObject("/spec/eval").set("gate", nextGate.deepCopy());
        return aligned;
    }

    static void requireGate(JsonNode oldGate, JsonNode nextGate) {
        var minimum = nextGate.path("minScore").asDouble(Double.NaN);
        var regression = nextGate.path("maxRegression").asDouble(Double.NaN);
        if (!Double.isFinite(minimum) || !Double.isFinite(regression) || minimum > 1 || regression < 0
                || minimum < oldGate.path("minScore").asDouble(.85)
                || regression > oldGate.path("maxRegression").asDouble(.02))
            throw invalid("对齐不得降低门禁");
    }

    private static Map<String, JsonNode> tools(JsonNode rows) {
        var result = new HashMap<String, JsonNode>();
        if (!rows.isArray()) throw invalid("工具须为数组");
        for (var row : rows) {
            var name = row.path("name").asText();
            if (name.isBlank() || result.put(name, normalized(row)) != null) throw invalid("工具名称无效或重复");
        }
        return result;
    }

    private static JsonNode normalized(JsonNode node) {
        JsonNode copy = node.deepCopy();
        if (copy.isObject()) {
            var object = (ObjectNode) copy;
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (var name : names) {
                if (object.get(name).isNull() || (name.equals("approval") && object.get(name).asText().equals("none"))) object.remove(name);
                else object.set(name, normalized(object.get(name)));
            }
        } else if (copy.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) copy;
            for (int i = 0; i < array.size(); i++) array.set(i, normalized(array.get(i)));
        }
        return copy;
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message);
    }
}
