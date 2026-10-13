package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Knowledge-base names chosen in the create wizard. */
public final class WizardKnowledge {
    private WizardKnowledge() {}

    public static List<String> resolve(JsonNode form, Set<String> catalog) {
        var node = form.path("knowledgeBases");
        if (!node.isArray() || node.isEmpty()) {
            return List.of();
        }
        var chosen = new LinkedHashSet<String>();
        node.forEach(item -> {
            var name = item.asText("").trim();
            if (!name.isBlank()) {
                chosen.add(name);
            }
        });
        var unknown = new ArrayList<String>();
        for (var name : chosen) {
            if (!catalog.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "知识库不在目录里：" + String.join("、", unknown));
        }
        return List.copyOf(chosen);
    }

    /** Quote a knowledge-base name for a single YAML scalar. */
    public static String yamlScalar(String name) {
        if (name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            return name;
        }
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
