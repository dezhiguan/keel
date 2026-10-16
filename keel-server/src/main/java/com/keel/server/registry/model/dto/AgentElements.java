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

    /** Upgrade a declared item only when a stored record already proves it. */
    public AgentElements withRuntime(boolean toolCall, boolean loop, boolean memory, boolean evalPassed, boolean audit) {
        return withRuntime(toolCall, loop, memory, evalPassed, audit, false, false);
    }

    public AgentElements withRuntime(boolean toolCall, boolean loop, boolean memory, boolean evalPassed,
                                     boolean audit, boolean versionedPrompt, boolean retrieval) {
        var next = new ArrayList<>(items);
        prove(next, "模型自己调工具", toolCall, "已有允许的工具调用");
        prove(next, "agent loop", loop, "运行里出现过多步循环");
        prove(next, "记忆", memory, "已有多轮会话或用户记忆记录");
        prove(next, "评测", evalPassed, "最近一次回归通过");
        prove(next, "可观测与审计", audit, "已有审计事件");
        prove(next, "有版本的提示词", versionedPrompt, "真实模型调用关联了提示词版本");
        prove(next, "知识检索", retrieval, "真实运行出现知识检索节点");
        return new AgentElements(kind, List.copyOf(next), missing);
    }

    private static void prove(List<Item> items, String name, boolean present, String detail) {
        if (!present) return;
        for (var i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (name.equals(item.name()) && !Boolean.FALSE.equals(item.passed())) {
                items.set(i, new Item(name, true, detail));
            }
        }
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
