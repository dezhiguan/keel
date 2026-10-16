package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.insight.TraceQueryService;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ElementEvidenceTest {
    @Test void onlyARealGenerationVersionAndRetrieverProveTheDeclaredElements() throws Exception {
        var jdbc = new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return requiredType.cast(0);
            }
        };
        var traces = new TraceQueryService(null, "", "", Map.of()) {
            @Override public Map<String, Object> list(int page, int size, String agent, String env) {
                assertThat(agent).isEqualTo("qa-lifecycle-1016");
                assertThat(env).isEqualTo("dev");
                return Map.of("items", List.of(
                        Map.of("traceId", "trace-1", "sessionId", "session-1"),
                        Map.of("traceId", "trace-2", "sessionId", "session-1")));
            }

            @Override public Map<String, Object> detail(String traceId) {
                return Map.of("nodes", List.of(
                        Map.of("type", "generation", "promptName", "qa-lifecycle-1016/answer", "promptVersion", 3),
                        Map.of("type", "retriever", "name", "knowledge.search"),
                        Map.of("type", "agent", "name", "agent.loop.step"),
                        Map.of("type", "tool", "name", "shift.policy"),
                        Map.of("type", "agent", "name", "agent.loop.step")));
            }
        };
        var agent = new Agent();
        agent.setName("qa-lifecycle-1016");
        var version = new AgentVersion();
        version.setEnv("dev");
        var manifest = new ObjectMapper().readTree("""
                {"spec":{"kind":"agent","capabilities":{"toolCalling":true,"loop":true,"memory":true},
                         "tools":[{"name":"shift.policy"}],
                         "prompts":{"source":"langfuse","items":[{"name":"answer"}]},
                         "knowledge":[{"kb":"平台基准库·v1"}]}}
                """);
        var detail = AgentDetail.of(agent, version, null, List.of(), manifest, List.of());
        var proved = new ElementEvidence(jdbc, traces).apply(detail);
        assertThat(proved.elements().items().get(3).passed()).isTrue();
        assertThat(proved.elements().items().get(5).passed()).isTrue();
        assertThat(proved.elements().items().get(0).passed()).isTrue();
        assertThat(proved.elements().items().get(1).passed()).isTrue();
        assertThat(proved.elements().items().get(2).passed()).isTrue();
    }
}
