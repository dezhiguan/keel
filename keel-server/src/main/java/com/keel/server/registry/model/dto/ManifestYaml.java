package com.keel.server.registry.model.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.List;

/** Turns a stored manifest snapshot into the agent.yaml the detail drawer shows. */
public final class ManifestYaml {
    private static final List<String> TOP_LEVEL = List.of("apiVersion", "kind", "metadata", "spec");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private ManifestYaml() {}

    public static String of(JsonNode manifest) {
        if (manifest == null || manifest.isNull() || manifest.isMissingNode()) {
            return null;
        }
        try {
            return YAML.writeValueAsString(ordered(manifest));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static JsonNode ordered(JsonNode manifest) {
        if (!manifest.isObject()) {
            return manifest;
        }
        ObjectNode ordered = JsonNodeFactory.instance.objectNode();
        for (var key : TOP_LEVEL) {
            if (manifest.has(key)) {
                ordered.set(key, manifest.get(key));
            }
        }
        manifest.fields().forEachRemaining(field -> {
            if (!ordered.has(field.getKey())) {
                ordered.set(field.getKey(), field.getValue());
            }
        });
        return ordered;
    }
}
