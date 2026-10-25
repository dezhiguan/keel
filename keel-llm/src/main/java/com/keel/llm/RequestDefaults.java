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
                    if (model.getKey().isBlank() || !valid(model.getValue(), true)) throw new IllegalArgumentException();
                }
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid scoped model request defaults");
        }
    }

    private static boolean valid(JsonNode options, boolean allowMemory) {
        if (!options.isObject()) return false;
        for (var fields=options.fields();fields.hasNext();) {
            var field=fields.next();var value=field.getValue();
            boolean accepted=switch(field.getKey()) {
                case "enable_thinking", "preserve_thinking" -> value.isBoolean();
                case "thinking_budget" -> value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>0;
                case "thinking" -> value.isObject() && value.size()==1 && value.path("type").isTextual()
                        && (value.path("type").asText().equals("enabled") || value.path("type").asText().equals("disabled"));
                case "memory", "interactive" -> allowMemory && valid(value, false);
                default -> false;
            };
            if (!accepted) return false;
        }
        return true;
    }

    String apply(ObjectNode original, String alias, String model, String path) {
        var body = original.deepCopy().put("model", model);
        if ("/v1/chat/completions".equals(path)) {
            var options=scopes.path(alias).path(model);
            options.fields().forEachRemaining(field -> {
                if (!java.util.Set.of("memory","interactive").contains(field.getKey()) && !body.has(field.getKey())
                        && !(field.getKey().equals("thinking_budget") && body.has("reasoning_effort")))
                    body.set(field.getKey(), field.getValue());
            });
            var purpose=body.path("metadata").path("keel_task").asText();
            boolean scoped=java.util.Set.of("memory","interactive").contains(purpose);
            if(body.path("metadata") instanceof ObjectNode metadata) metadata.remove("keel_task");
            if(scoped && options.path(purpose).isObject()) {
                for(var key:java.util.List.of("enable_thinking","preserve_thinking","thinking_budget","thinking","reasoning_effort","reasoning")) body.remove(key);
                options.path(purpose).fields().forEachRemaining(field->body.set(field.getKey(),field.getValue()));
            }
        }
        return body.toString();
    }
}
