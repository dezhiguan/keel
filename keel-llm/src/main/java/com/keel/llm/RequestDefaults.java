package com.keel.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Operator defaults scoped to an authenticated virtual key and the actual upstream model. */
final class RequestDefaults {
    private final JsonNode scopes;

    RequestDefaults(String json) {
        try {
            scopes = new ObjectMapper().readTree(json == null || json.isBlank() ? "{}" : json);
            if (!scopes.isObject()) throw new IllegalArgumentException();
            for (var aliases = scopes.fields(); aliases.hasNext();) {
                var alias = aliases.next();
                if (!Gateway.ALIAS.matcher(alias.getKey()).matches() || !alias.getValue().isObject())
                    throw new IllegalArgumentException();
                for (var models = alias.getValue().fields(); models.hasNext();) {
                    var model = models.next();
                    if (model.getKey().isBlank() || !model.getValue().isObject()) throw new IllegalArgumentException();
                    for (var fields = model.getValue().fields(); fields.hasNext();) {
                        var field = fields.next();
                        var valid = switch (field.getKey()) {
                            case "enable_thinking", "preserve_thinking" -> field.getValue().isBoolean();
                            case "thinking_budget" -> field.getValue().isIntegralNumber()
                                    && field.getValue().canConvertToInt() && field.getValue().intValue() > 0;
                            case "thinking" -> field.getValue().isObject() && field.getValue().size() == 1
                                    && field.getValue().path("type").isTextual()
                                    && (field.getValue().path("type").asText().equals("enabled")
                                    || field.getValue().path("type").asText().equals("disabled"));
                            default -> false;
                        };
                        if (!valid) throw new IllegalArgumentException();
                    }
                }
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid scoped model request defaults");
        }
    }

    String apply(ObjectNode original, String alias, String model, String path) {
        var body = original.deepCopy().put("model", model);
        if ("/v1/chat/completions".equals(path)) {
            scopes.path(alias).path(model).fields().forEachRemaining(field -> {
                if (!body.has(field.getKey())
                        && !(field.getKey().equals("thinking_budget") && body.has("reasoning_effort"))) {
                    body.set(field.getKey(), field.getValue());
                }
            });
        }
        return body.toString();
    }
}
