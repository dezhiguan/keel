package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Builds an agent.yaml preview. It does not write the registry. */
public final class ManifestPreview {
    private ManifestPreview() {}

    public static Result render(JsonNode form) {
        var warnings = new ArrayList<String>();
        var name = text(form, "name");
        var rejection = AgentNames.rejection(name);
        if (rejection != null) {
            warnings.add(rejection);
        }
        var endpoint = text(form, "endpoint");
        if (endpoint.isBlank()) {
            endpoint = "http://" + (name.isBlank() ? "agent" : name) + ".agents.svc:8000";
            warnings.add("未填写 endpoint，预览使用了占位地址");
        }
        var language = text(form, "language").toLowerCase();
        if (!language.equals("python") && !language.equals("java") && !language.equals("other")) {
            language = "python";
        }
        var runtime = text(form, "runtime");
        if (runtime.isBlank()) {
            runtime = "code";
        }
        var owner = (text(form, "ownerOrg") + " / " + text(form, "ownerUser")).trim();
        var yaml = """
                apiVersion: keel/v1
                kind: Agent
                metadata:
                  name: %s
                  displayName: %s
                  owner: %s
                spec:
                  runtime:
                    type: %s
                    language: %s
                    endpoint: %s
                    liveness: %s
                """.formatted(name, text(form, "displayName"), owner, runtime, language, endpoint,
                "dify".equals(runtime) ? "probe" : "k8s");
        return new Result(yaml.stripTrailing() + "\n", List.copyOf(warnings));
    }

    private static String text(JsonNode form, String field) {
        var node = form.get(field);
        return node == null || node.isNull() ? "" : node.asText();
    }

    public record Result(String yaml, List<String> warnings) {}
}
