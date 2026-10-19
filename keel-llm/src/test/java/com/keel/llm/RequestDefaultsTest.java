package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RequestDefaultsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIG = """
        {"meta-agent-dev":{"qwen3.8-flash":{"thinking_budget":2048,"preserve_thinking":false}}}
        """;

    @Test void actualForwardingUsesAuthenticatedAliasAndPreservesExplicitOptions() throws Exception {
        var seen = new ArrayList<ObjectNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seen.add((ObjectNode) JSON.readTree(exchange.getRequestBody()));
            var response = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var name = "qwen3.8-flash";
            var catalog = new ModelCatalog(Map.of(name, new ModelCatalog.Model(name,
                    new BigDecimal("0.000001"), new BigDecimal("0.000002"),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", 4096, 4096)));
            var gateway = new Gateway(catalog, new BudgetCounter(), new Upstream(), new KeyDirectory(),
                    new SpendLog(null), null, new Admission(null), new ProbeMarks(null),
                    new RoutePolicy(), new AlertRules(null), new RequestDefaults(CONFIG));
            var meta = gateway.create("meta-agent-dev", List.of(name), List.of(), BigDecimal.TEN, false);
            var other = gateway.create("other-agent-dev", List.of(name), List.of(), BigDecimal.TEN, false);
            var request = "{\"model\":\"qwen3.8-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"draft\"}]}";
            gateway.complete("Bearer " + meta.key(), "/v1/chat/completions", request, Duration.ofSeconds(3));
            var forged = (ObjectNode) JSON.readTree(request);
            forged.putObject("metadata").put("caller_agent", "meta-agent");
            gateway.complete("Bearer " + other.key(), "/v1/chat/completions", forged.toString(), Duration.ofSeconds(3));
            var explicit = (ObjectNode) JSON.readTree(request);
            explicit.put("thinking_budget", 4096).put("preserve_thinking", true);
            gateway.complete("Bearer " + meta.key(), "/v1/chat/completions", explicit.toString(), Duration.ofSeconds(3));
            var effort = (ObjectNode) JSON.readTree(request);
            effort.put("reasoning_effort", "medium");
            gateway.complete("Bearer " + meta.key(), "/v1/chat/completions", effort.toString(), Duration.ofSeconds(3));
            assertThat(seen.get(0).path("thinking_budget").asInt()).isEqualTo(2048);
            assertThat(seen.get(0).path("preserve_thinking").asBoolean()).isFalse();
            assertThat(seen.get(0).get("messages")).isEqualTo(JSON.readTree(request).get("messages"));
            assertThat(seen.get(1).has("thinking_budget")).isFalse();
            assertThat(seen.get(2).path("thinking_budget").asInt()).isEqualTo(4096);
            assertThat(seen.get(2).path("preserve_thinking").asBoolean()).isTrue();
            assertThat(seen.get(3).has("thinking_budget")).isFalse();
            assertThat(gateway.spend("meta-agent-dev", 1, 10)).hasSize(3);
        } finally {
            server.stop(0);
        }
    }

    @Test void otherModelsAndEmbeddingsRemainUnchanged() throws Exception {
        var defaults = new RequestDefaults(CONFIG);
        var body = (ObjectNode) JSON.readTree("{\"model\":\"qwen3.8-flash\",\"input\":\"text\"}");
        assertThat(JSON.readTree(defaults.apply(body, "meta-agent-dev", "qwen3.8-flash", "/v1/embeddings"))).isEqualTo(body);
        assertThat(JSON.readTree(defaults.apply(body, "meta-agent-dev", "another-model", "/v1/chat/completions"))
                .has("thinking_budget")).isFalse();
        assertThat(body.has("thinking_budget")).isFalse();
    }

    @Test void deepseekThinkingObjectIsScopedAndExplicitRequestWins() throws Exception {
        var defaults = new RequestDefaults("""
            {"qa-routing-1019-prod":{"deepseek-flash":{"thinking":{"type":"disabled"}}}}
            """);
        var original = (ObjectNode) JSON.readTree("{\"messages\":[]}");
        var applied = JSON.readTree(defaults.apply(original, "qa-routing-1019-prod", "deepseek-flash", "/v1/chat/completions"));
        assertThat(applied.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(original.has("thinking")).isFalse();
        assertThat(JSON.readTree(defaults.apply(original, "another-prod", "deepseek-flash", "/v1/chat/completions"))
                .has("thinking")).isFalse();
        assertThat(JSON.readTree(defaults.apply(original, "qa-routing-1019-prod", "qwen3.8-flash", "/v1/chat/completions"))
                .has("thinking")).isFalse();
        original.putObject("thinking").put("type", "enabled");
        assertThat(JSON.readTree(defaults.apply(original, "qa-routing-1019-prod", "deepseek-flash", "/v1/chat/completions"))
                .path("thinking").path("type").asText()).isEqualTo("enabled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "[]", "{}", "{\"type\":false}", "{\"type\":\"auto\"}",
            "{\"type\":\"disabled\",\"model\":\"other\"}", "{\"type\":\"disabled\",\"budget\":100}"})
    void malformedThinkingObjectsRefuseStartup(String thinking) {
        assertThatThrownBy(() -> new RequestDefaults("{\"qa-routing-1019-prod\":{\"deepseek-flash\":{\"thinking\":"
                + thinking + "}}}" )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "broken", "{\"meta-agent-dev\":[]}",
            "{\"meta-agent-dev\":{\"qwen3.8-flash\":{\"model\":\"another-model\"}}}",
            "{\"meta-agent-dev\":{\"qwen3.8-flash\":{\"thinking_budget\":0}}}",
            "{\"meta-agent-dev\":{\"qwen3.8-flash\":{\"enable_thinking\":\"false\"}}}"})
    void invalidOrIdentityChangingConfigurationRefusesStartup(String config) {
        assertThatThrownBy(() -> new RequestDefaults(config)).isInstanceOf(IllegalArgumentException.class);
    }
}
