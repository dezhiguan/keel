package com.keel.server.registry.model.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentInstance;
import com.keel.server.registry.model.entity.AgentVersion;

import java.util.List;

/** Detail drawer. Self-check, resources and findings stay empty until later tasks. */
public record AgentDetail(
        @JsonUnwrapped AgentSummary summary,
        String manifestYaml,
        String manifestHash,
        List<String> knowledgeBases,
        List<String> tools,
        List<String> models,
        List<String> delegates,
        List<InstanceRow> instanceList,
        List<Object> resources,
        List<Object> versions,
        List<Object> findings) {

    public static AgentDetail of(Agent agent, AgentVersion latest, String instances,
                                 List<AgentInstance> rows, JsonNode manifest) {
        var summary = AgentSummary.of(agent, latest, instances, manifest);
        return new AgentDetail(summary, ManifestYaml.of(manifest), latest == null ? null : latest.getManifestHash(),
                texts(manifest, "knowledge", "kb"),
                texts(manifest, "tools", "name"),
                models(manifest),
                delegates(manifest),
                rows.stream().map(InstanceRow::of).toList(),
                List.of(), List.of(), List.of());
    }

    private static List<String> texts(JsonNode manifest, String array, String field) {
        if (manifest == null) {
            return List.of();
        }
        var spec = manifest.path("spec").path(array);
        if (!spec.isArray()) {
            return List.of();
        }
        var values = new java.util.ArrayList<String>();
        spec.forEach(item -> {
            var value = item.path(field).asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private static List<String> models(JsonNode manifest) {
        if (manifest == null) {
            return List.of();
        }
        var models = manifest.path("spec").path("models");
        var values = new java.util.ArrayList<String>();
        if (models.hasNonNull("default")) {
            values.add(models.get("default").asText());
        }
        models.path("fallback").forEach(item -> values.add(item.asText()));
        return values;
    }

    private static List<String> delegates(JsonNode manifest) {
        if (manifest == null) {
            return List.of();
        }
        var values = new java.util.ArrayList<String>();
        manifest.path("spec").path("delegates").forEach(item -> values.add(item.asText()));
        return values;
    }

    public AgentDetail withSummary(AgentSummary summary) {
        return new AgentDetail(summary, manifestYaml, manifestHash, knowledgeBases, tools, models, delegates,
                instanceList, resources, versions, findings);
    }

    public record InstanceRow(String instanceId, String source, String version, boolean ready, String lastSeenAt) {
        static InstanceRow of(AgentInstance row) {
            return new InstanceRow(row.getInstanceId(), row.getSource(), row.getVersion(), row.isReady(),
                    row.getLastSeenAt() == null ? null : row.getLastSeenAt().toString());
        }
    }
}
