package com.keel.starter.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The subset of JSON Schema 2020-12 used by keel/v1, evaluated from the contract files. */
public final class SchemaSubset {
    private static final Set<String> SUPPORTED = Set.of(
            "$schema", "$id", "$defs", "$comment", "$ref", "title", "description", "default",
            "type", "required", "properties", "items", "additionalProperties", "enum", "const",
            "minLength", "pattern", "format", "minimum", "maximum", "minItems", "maxItems",
            "allOf", "oneOf", "if", "then", "else");

    private SchemaSubset() {}

    public static void assertSupported(JsonNode schema) {
        for (Iterator<Map.Entry<String, JsonNode>> it = schema.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> field = it.next();
            String keyword = field.getKey();
            if (!SUPPORTED.contains(keyword)) throw new IllegalArgumentException("Unsupported schema keyword: " + keyword);
            JsonNode child = field.getValue();
            if (keyword.equals("properties")) {
                child.elements().forEachRemaining(SchemaSubset::assertSupported);
            } else if (keyword.equals("$defs")) {
                for (Iterator<Map.Entry<String, JsonNode>> defs = child.fields(); defs.hasNext();) {
                    Map.Entry<String, JsonNode> definition = defs.next();
                    if (definition.getKey().equals("frames")) {
                        definition.getValue().elements().forEachRemaining(SchemaSubset::assertSupported);
                    } else {
                        assertSupported(definition.getValue());
                    }
                }
            } else if (Set.of("items", "if", "then", "else").contains(keyword)
                    || (keyword.equals("additionalProperties") && child.isObject())) {
                assertSupported(child);
            } else if (keyword.equals("allOf") || keyword.equals("oneOf")) {
                child.elements().forEachRemaining(SchemaSubset::assertSupported);
            }
        }
    }

    public static List<String> errors(JsonNode root, JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        validate(root, schema, value, "", errors);
        return errors;
    }

    private static void validate(JsonNode root, JsonNode schema, JsonNode value,
                                 String path, List<String> errors) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            if (!ref.startsWith("#/")) throw new IllegalArgumentException("External schema ref: " + ref);
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) throw new IllegalArgumentException("Unresolved schema ref: " + ref);
            validate(root, target, value, path, errors);
        }
        if (schema.has("type") && !matches(schema.get("type").asText(), value)) {
            errors.add(path);
            return;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) errors.add(path);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) if (option.equals(value)) found = true;
            if (!found) errors.add(path);
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (schema.has("minLength") && text.codePointCount(0, text.length()) < schema.get("minLength").asInt()) errors.add(path);
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(text).find()) errors.add(path);
            if (schema.has("format")) {
                try {
                    switch (schema.get("format").asText()) {
                        case "date-time" -> OffsetDateTime.parse(text);
                        case "uri" -> {
                            if (!URI.create(text).isAbsolute()) errors.add(path);
                        }
                        default -> throw new IllegalArgumentException("Unsupported format: " + schema.get("format"));
                    }
                } catch (RuntimeException invalid) {
                    errors.add(path);
                }
            }
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) errors.add(path);
            if (schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0) errors.add(path);
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) errors.add(path);
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) errors.add(path);
            if (schema.has("items")) {
                for (int index = 0; index < value.size(); index++)
                    validate(root, schema.get("items"), value.get(index), path + "/" + index, errors);
            }
        }
        if (value.isObject()) {
            if (schema.has("required")) {
                for (JsonNode required : schema.get("required")) {
                    String field = required.asText();
                    if (!value.has(field)) errors.add(path + "/" + escape(field));
                }
            }
            JsonNode properties = schema.path("properties");
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> field = it.next();
                if (properties.has(field.getKey())) {
                    validate(root, properties.get(field.getKey()), field.getValue(),
                            path + "/" + escape(field.getKey()), errors);
                } else if (schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean()
                        && !schema.get("additionalProperties").asBoolean()) {
                    errors.add(path + "/" + escape(field.getKey()));
                }
            }
        }
        if (schema.has("allOf")) {
            for (JsonNode part : schema.get("allOf")) validate(root, part, value, path, errors);
        }
        if (schema.has("if")) {
            List<String> conditional = new ArrayList<>();
            validate(root, schema.get("if"), value, path, conditional);
            if (conditional.isEmpty() && schema.has("then")) validate(root, schema.get("then"), value, path, errors);
            if (!conditional.isEmpty() && schema.has("else")) validate(root, schema.get("else"), value, path, errors);
        }
        if (schema.has("oneOf")) {
            int accepted = 0;
            for (JsonNode part : schema.get("oneOf")) {
                if (errors(root, part, value).isEmpty()) accepted++;
            }
            if (accepted != 1) errors.add(path);
        }
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> throw new IllegalArgumentException("Unsupported schema type: " + type);
        };
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
