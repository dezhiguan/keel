package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.langfuse.LangfuseRateLimit;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ElementEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate jdbc = new JdbcTemplate() {
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) { return type.cast(0); }
    };
    @Test void cachesOneBoundedSampleButInvalidatesOnManifestOrRegisteredVersionChange() throws Exception {
        var calls = new AtomicInteger();
        var client = new LangfuseClient("", "", "") {
            @Override public JsonNode elementObservations(java.time.Instant from) {
                calls.incrementAndGet();
                return json.createObjectNode().set("data", json.createArrayNode().add(json.createObjectNode()
                        .put("traceId","t1").put("type","GENERATION").set("metadata",json.createObjectNode()
                                .put("attributes.keel.agent","qa").put("attributes.keel.env","dev")
                                .put("attributes.langfuse.observation.prompt.name","qa/role")
                                .put("attributes.langfuse.observation.prompt.version",3))));
            }
        };
        var service = new ElementEvidence(jdbc,client);
        var first = service.report(detail("hash1","v1",true));
        assertThat(first.elements().items().get(3).passed()).isTrue();
        assertThat(first.unavailable()).isEmpty();
        service.report(detail("hash1","v1",true));
        assertThat(calls.get()).isEqualTo(1);
        service.report(detail("hash2","v1",true));
        service.report(detail("hash2","v2",true));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test void rateLimitsRemainUnavailableInsteadOfFalseAndAreBrieflyCached() throws Exception {
        var calls = new AtomicInteger();
        var client = new LangfuseClient("", "", "") {
            @Override public JsonNode elementObservations(java.time.Instant from) {
                calls.incrementAndGet(); throw new LangfuseRateLimit(60);
            }
        };
        var service = new ElementEvidence(jdbc,client);
        var detail = detail("hash","v1",true);
        var result = service.report(detail);
        assertThat(result.unavailable()).contains("有版本的提示词");
        assertThat(result.elements().items().get(3).passed()).isNull();
        assertThat(result.elements().items().get(0).passed()).isFalse();
        service.report(detail);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void undeclaredElementsDoNotCauseAnExternalRead() throws Exception {
        var client = new LangfuseClient("", "", "") {
            @Override public JsonNode elementObservations(java.time.Instant from) { throw new AssertionError("unexpected external query"); }
        };
        assertThat(new ElementEvidence(jdbc,client).report(detail("hash","v1",false)).unavailable()).isEmpty();
    }

    private AgentDetail detail(String hash, String number, boolean prompts) throws Exception {
        var agent = new Agent(); agent.setName("qa");
        var version = new AgentVersion(); version.setEnv("dev"); version.setVersion(number); version.setManifestHash(hash);
        var manifest = json.readTree(prompts ? "{\"spec\":{\"prompts\":{\"source\":\"langfuse\",\"items\":[{\"name\":\"role\"}]}}}" : "{\"spec\":{}}");
        return AgentDetail.of(agent,version,null,List.of(),manifest,List.of());
    }
}
