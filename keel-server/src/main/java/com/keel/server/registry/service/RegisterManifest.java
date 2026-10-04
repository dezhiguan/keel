package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Turns a console RegisterRequest into the manifest provisioning reads. */
public final class RegisterManifest {
    private RegisterManifest() {}

    public static ManifestPreview.Result render(JsonNode form, int port) {
        var warnings = new ArrayList<String>();
        var name = text(form, "name");
        var rejection = AgentNames.rejection(name);
        if (rejection != null) {
            warnings.add(rejection);
        }
        var supplied = text(form, "endpoint");
        var endpoint = endpoint(form, port);
        if (supplied.isBlank() && !"echo".equals(text(form, "template"))) {
            warnings.add("未填写 endpoint，预览使用了占位地址");
        }
        var language = language(form);
        var runtime = runtime(form);
        var owner = owner(form);
        var model = model(form);
        var budget = budget(form);
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
                  auth:
                    audience: %s
                  models:
                    default: %s
                    budget:
                      dailyCny: %s
                  eval:
                    dataset: %s/smoke
                """.formatted(name, text(form, "displayName"), owner, runtime, language, endpoint,
                "dify".equals(runtime) ? "probe" : "k8s", name.isBlank() ? "agent" : name, model,
                budget.stripTrailingZeros().toPlainString(), name.isBlank() ? "agent" : name);
        return new ManifestPreview.Result(yaml.stripTrailing() + "\n", List.copyOf(warnings));
    }

    public static ObjectNode toNode(com.fasterxml.jackson.databind.ObjectMapper json, JsonNode body, int port) {
        if (body.has("apiVersion")) {
            return (ObjectNode) body;
        }
        ObjectNode manifest = json.createObjectNode();
        manifest.put("apiVersion", "keel/v1");
        manifest.put("kind", "Agent");
        var metadata = manifest.putObject("metadata");
        var name = text(body, "name");
        metadata.put("name", name);
        metadata.put("displayName", text(body, "displayName").isBlank() ? name : text(body, "displayName"));
        metadata.put("owner", owner(body));
        var spec = manifest.putObject("spec");
        var runtime = spec.putObject("runtime");
        var runtimeType = runtime(body);
        runtime.put("type", runtimeType);
        runtime.put("language", language(body));
        runtime.put("endpoint", endpoint(body, port));
        runtime.put("liveness", "dify".equals(runtimeType) ? "probe" : "k8s");
        spec.putObject("auth").put("audience", name);
        var models = spec.putObject("models");
        models.put("default", model(body));
        var fallback = models.putArray("fallback");
        var fallbackNode = body.path("models").path("fallback");
        if (fallbackNode.isArray()) {
            fallbackNode.forEach(item -> {
                if (!item.asText("").isBlank()) {
                    fallback.add(item.asText());
                }
            });
        }
        models.putObject("budget").put("dailyCny", budget(body).doubleValue());
        spec.putObject("eval").put("dataset", (name.isBlank() ? "agent" : name) + "/smoke");
        return manifest;
    }

    public static String endpoint(JsonNode form, int port) {
        var name = text(form, "name");
        if ("echo".equals(text(form, "template"))) {
            return "http://echo-agent.keel-system.svc.cluster.local:8000";
        }
        var endpoint = text(form, "endpoint");
        if (endpoint.isBlank()) {
            endpoint = "http://" + (name.isBlank() ? "agent" : name) + ".agents.svc:8000";
        }
        return endpoint;
    }

    static boolean missingExternal(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("未配置") || message.contains("材料不齐") || message.contains("缺失") || message.contains("找不到评测");
    }

    private static String owner(JsonNode form) {
        if (form.has("apiVersion")) {
            return text(form.path("metadata"), "owner");
        }
        var org = text(form, "ownerOrg");
        var user = text(form, "ownerUser");
        if (org.isBlank() && user.isBlank()) {
            return "";
        }
        return org + " / " + user;
    }

    private static String runtime(JsonNode form) {
        var runtime = text(form, "runtime");
        return runtime.isBlank() ? "code" : runtime;
    }

    private static String language(JsonNode form) {
        var language = text(form, "language").toLowerCase();
        if (!language.equals("python") && !language.equals("java") && !language.equals("other")) {
            return "python";
        }
        return language;
    }

    private static String model(JsonNode form) {
        var nested = form.path("models").path("default").asText("");
        if (!nested.isBlank()) {
            return nested;
        }
        var flat = text(form, "model");
        return flat.isBlank() ? "qwen-plus" : flat;
    }

    private static java.math.BigDecimal budget(JsonNode form) {
        var node = form.path("models").path("dailyBudgetCny");
        if (node.isNumber()) {
            return node.decimalValue();
        }
        var flat = form.path("dailyBudgetCny");
        if (flat.isNumber()) {
            return flat.decimalValue();
        }
        return java.math.BigDecimal.valueOf(30);
    }

    private static String text(JsonNode form, String field) {
        var node = form.get(field);
        return node == null || node.isNull() ? "" : node.asText();
    }
}
