package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregate only the selected agent/environment, with loop evidence scoped to one trace. */
final class ElementObservations {
    record Evidence(boolean prompt, boolean retrieval, boolean loop, boolean tool, boolean session) {}

    static Evidence read(JsonNode body, String agent, String env) {
        if (body == null || !body.path("data").isArray()) throw new IllegalStateException("运行证据响应格式无效");
        boolean prompt = false, retrieval = false;
        var steps = new HashMap<String, java.util.Set<String>>();
        var tools = new HashSet<String>();
        var sessions = new HashMap<String, java.util.Set<String>>();
        for (var row : body.path("data")) {
            var meta = metadata(row.path("metadata"));
            var observedEnv = meta.getOrDefault("keel.env", row.path("environment").asText());
            if ("production".equals(observedEnv)) observedEnv = "prod";
            if (!agent.equals(meta.get("keel.agent")) || !env.equals(observedEnv)) continue;
            var trace = row.path("traceId").asText("");
            if (trace.isBlank()) continue;
            var type = meta.getOrDefault("langfuse.observation.type", row.path("type").asText()).toLowerCase(java.util.Locale.ROOT);
            var session = meta.getOrDefault("langfuse.session.id", row.path("sessionId").asText(""));
            if (!session.isBlank()) sessions.computeIfAbsent(session, ignored -> new HashSet<>()).add(trace);
            if ("agent.loop.step".equals(row.path("name").asText()))
                steps.computeIfAbsent(trace, ignored -> new HashSet<>()).add(row.path("id").asText());
            if ("tool".equals(type)) tools.add(trace);
            if ("retriever".equals(type)) retrieval = true;
            if ("generation".equals(type)) {
                var name = meta.getOrDefault("langfuse.observation.prompt.name", "");
                try { prompt |= !name.isBlank() && Integer.parseInt(meta.getOrDefault("langfuse.observation.prompt.version", "0")) > 0; }
                catch (NumberFormatException ignored) { /* No version proof. */ }
            }
        }
        boolean loop = steps.values().stream().anyMatch(ids -> ids.size() > 1);
        boolean tool = steps.entrySet().stream().anyMatch(e -> e.getValue().size() > 1 && tools.contains(e.getKey()));
        return new Evidence(prompt, retrieval, loop, tool, sessions.values().stream().anyMatch(ids -> ids.size() > 1));
    }

    private static Map<String, String> metadata(JsonNode node) {
        var out = new LinkedHashMap<String, String>();
        for (var source : java.util.List.of(node, node.path("attributes"), node.path("resourceAttributes"))) {
            source.properties().forEach(entry -> {
                var key = entry.getKey().startsWith("attributes.") ? entry.getKey().substring(11) : entry.getKey();
                if (entry.getValue().isValueNode() && !entry.getValue().isNull()) out.putIfAbsent(key, entry.getValue().asText());
            });
        }
        return out;
    }
}
