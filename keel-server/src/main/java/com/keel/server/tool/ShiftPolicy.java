package com.keel.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** Platform duty-policy text. The shared tool does not call another service. */
public final class ShiftPolicy {
    private static final Map<String, String> TEXT = Map.of(
            "故障", "故障处置：先确认影响面，15分钟未恢复则升级值班长。禁止未授权重启生产。",
            "变更", "变更处置：先核对变更窗口和回退方案，窗口外的变更升级变更经理。禁止跳过回退方案直接上线。",
            "咨询", "咨询处置：先给出来源和适用范围，无法确认时升级知识库负责人。禁止把猜测说成已发布口径。");

    private ShiftPolicy() {}

    public static boolean known(String category) {
        return TEXT.containsKey(category);
    }

    public static ObjectNode answer(JsonNode body) {
        var category = body == null ? "" : body.path("category").asText("").trim();
        var node = JsonNodeFactory.instance.objectNode();
        node.put("category", category);
        node.put("text", TEXT.getOrDefault(category, ""));
        return node;
    }
}
