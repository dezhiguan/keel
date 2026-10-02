package com.keel.starter.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keel.common.model.AgentManifest;
import com.keel.common.model.AuditEvent;
import com.keel.common.model.ErrorEvent;
import com.keel.common.model.FinalEvent;
import com.keel.common.model.StepEvent;
import com.keel.common.model.SuspendEvent;
import com.keel.common.model.TokenEvent;
import com.keel.common.model.ToolEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

class ContractConformanceTest {
    private static final Path SUITE = Path.of("..", "contracts", "tests").toAbsolutePath().normalize();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Yaml yaml = new Yaml();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cases() throws Exception {
        try (var input = Files.newInputStream(SUITE.resolve("cases.yaml"))) {
            return yaml.load(input);
        }
    }

    @TestFactory
    Stream<DynamicTest> contractCases() throws Exception {
        return cases().stream().map(entry -> DynamicTest.dynamicTest((String) entry.get("file"), () -> check(entry)));
    }

    @Test
    void casesCoverEveryFixture() throws Exception {
        Set<String> listed = new HashSet<>();
        for (Map<String, Object> entry : cases()) listed.add((String) entry.get("file"));
        Set<String> files = new HashSet<>();
        try (Stream<Path> paths = Files.walk(SUITE.resolve("fixtures"))) {
            paths.filter(Files::isRegularFile).forEach(path ->
                    files.add(SUITE.resolve("fixtures").relativize(path).toString().replace('\\', '/')));
        }
        assertEquals(files, listed);
    }

    private void check(Map<String, Object> entry) throws Exception {
        String file = (String) entry.get("file");
        Path path = SUITE.resolve("fixtures").resolve(file);
        JsonNode value;
        if (file.endsWith(".yaml")) {
            try (var input = Files.newInputStream(path)) {
                value = mapper.valueToTree(yaml.load(input));
            }
        } else {
            value = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        }
        String family = file.split("/")[0];
        if (family.equals("canonical")) family = "audit-event";
        JsonNode schema = mapper.readTree(Files.readString(SUITE.getParent().resolve(family + ".schema.json")));
        SchemaSubset.assertSupported(schema);
        List<String> errors = new ArrayList<>(SchemaSubset.errors(schema, schema, value));
        if (family.equals("sse-events") && value.path("event").isTextual()) {
            ObjectNode selected = mapper.createObjectNode();
            selected.set("$defs", schema.get("$defs"));
            selected.put("$ref", "#/$defs/frames/" + value.get("event").asText());
            List<String> selectedErrors = SchemaSubset.errors(selected, selected, value);
            if (!selectedErrors.isEmpty()) {
                errors.clear();
                errors.addAll(selectedErrors);
            }
        }
        if (family.equals("manifest") && entry.containsKey("registered_agents")) {
            @SuppressWarnings("unchecked")
            Set<String> registered = new HashSet<>((List<String>) entry.get("registered_agents"));
            JsonNode delegates = value.path("spec").path("delegates");
            for (int i = 0; i < delegates.size(); i++) {
                if (!registered.contains(delegates.get(i).asText())) errors.add("/spec/delegates/" + i);
            }
        }
        if (family.equals("audit-event") && entry.containsKey("capture_fields")) {
            @SuppressWarnings("unchecked")
            Set<String> allowed = new HashSet<>((List<String>) entry.get("capture_fields"));
            value.path("payload").fieldNames().forEachRemaining(key -> {
                if (!allowed.contains(key)) errors.add("/payload/" + key.replace("~", "~0").replace("/", "~1"));
            });
        }
        String digest = null;
        if (errors.isEmpty()) {
            if (family.equals("manifest")) {
                mapper.treeToValue(value, AgentManifest.class);
            } else if (family.equals("sse-events")) {
                parseEvent(value);
            } else {
                AuditEvent event = mapper.treeToValue(value, AuditEvent.class);
                if (file.startsWith("canonical/")) {
                    try {
                        digest = CanonicalAudit.sha256(event);
                    } catch (IllegalArgumentException invalidFloat) {
                        errors.add(invalidFloat.getMessage());
                    }
                }
            }
        }
        if ("reject".equals(entry.get("expect"))) {
            assertEquals(Set.of(entry.get("reason")), new HashSet<>(errors), file + ": " + errors);
        } else {
            assertTrue(errors.isEmpty(), file + ": " + errors);
            if (entry.containsKey("canonical_sha256")) assertEquals(entry.get("canonical_sha256"), digest, file);
        }
    }

    private void parseEvent(JsonNode frame) throws Exception {
        Class<?> type = switch (frame.path("event").asText()) {
            case "step" -> StepEvent.class;
            case "tool" -> ToolEvent.class;
            case "token" -> TokenEvent.class;
            case "final" -> FinalEvent.class;
            case "error" -> ErrorEvent.class;
            case "suspend" -> SuspendEvent.class;
            default -> throw new IllegalArgumentException("Unknown SSE event: " + frame.path("event"));
        };
        mapper.treeToValue(frame.get("data"), type);
    }
}
