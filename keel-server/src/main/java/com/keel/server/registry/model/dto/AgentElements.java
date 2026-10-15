package com.keel.server.registry.model.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Manifest evidence for the twelve agent elements; declarations are not runtime proof. */
public record AgentElements(String kind, List<Item> items, List<String> missing) {
    public record Item(String name, Boolean passed, String detail) {}

    public static AgentElements of(JsonNode manifest) {
        if (manifest == null || manifest.isMissingNode() || manifest.isNull()) return null;
        var spec = manifest.path("spec");
        var capabilities = spec.path("capabilities");
        boolean toolCalling = capabilities.path("toolCalling").asBoolean(false) && !spec.path("tools").isEmpty();
        boolean loop = capabilities.path("loop").asBoolean(false);
        boolean memory = capabilities.path("memory").asBoolean(false);
        boolean prompts = "langfuse".equals(spec.path("prompts").path("source").asText())
                && !spec.path("prompts").path("items").isEmpty();
        boolean eval = !spec.path("eval").path("dataset").asText("").isBlank();
        var missing = new ArrayList<String>();
        if (!toolCalling) missing.add("模型自己调工具");
        if (!loop) missing.add("agent loop");
        if (!memory) missing.add("记忆");
        if (!prompts) missing.add("有版本的提示词");
        if (!eval) missing.add("评测");
        var items = new ArrayList<Item>();
        items.add(core("模型自己调工具", toolCalling));
        items.add(core("agent loop", loop));
        items.add(core("记忆", memory));
        items.add(core("有版本的提示词", prompts));
        items.add(declared("模型接入", !spec.path("models").path("default").asText("").isBlank()));
        items.add(optional("知识检索", !spec.path("knowledge").isEmpty()));
        items.add(core("评测", eval));
        boolean guardrails = spec.path("guardrails").isObject() && approvalsBound(spec);
        items.add(declared("护栏与权限", guardrails));
        boolean risky = false;
        for (var tool : spec.path("tools")) risky |= "high".equals(tool.path("risk").asText());
        items.add(new Item("人在回路", null, risky ? "高风险工具已声明审批；挂起恢复待实测" : "无高风险工具"));
        items.add(optional("可观测与审计", spec.path("audit").isObject()));
        boolean identity = !spec.path("auth").path("audience").asText("").isBlank()
                && spec.path("models").path("budget").path("dailyCny").asDouble(0) > 0;
        items.add(declared("身份与预算", identity));
        items.add(optional("多智能体委派", !spec.path("delegates").isEmpty()));
        return new AgentElements("agent".equals(spec.path("kind").asText()) ? "agent" : "workflow",
                List.copyOf(items), List.copyOf(missing));
    }

    private static Item core(String name, boolean present) {
        return new Item(name, present ? null : false,
                present ? "manifest 已声明；运行行为待门禁验证" : "manifest 缺少核心声明");
    }

    private static Item declared(String name, boolean present) {
        return new Item(name, present ? true : false, present ? "manifest 已声明" : "manifest 未声明");
    }

    private static Item optional(String name, boolean present) {
        return new Item(name, null, present ? "manifest 已声明；运行行为待验证" : "未声明（可选）");
    }

    private static boolean approvalsBound(JsonNode spec) {
        for (var tool : spec.path("tools")) {
            if ("high".equals(tool.path("risk").asText())
                    && !"required".equals(tool.path("approval").asText())) return false;
        }
        return true;
    }
}
