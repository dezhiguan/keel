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

    @Test void undeclaredMemoryIsProvedByLocalSessionHistoryWithoutExternalRequests() throws Exception {
        var database = new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.contains("session_message")) {
                    assertThat(args).containsExactly("qa", "dev");
                    return type.cast(1);
                }
                return type.cast(0);
            }
        };
        var client = new LangfuseClient("", "", "") {
            @Override public JsonNode elementObservations(java.time.Instant from) { throw new AssertionError("memory must not query Langfuse"); }
        };
        var result = new ElementEvidence(database,client).report(detail("hash","v1",false));
        var memory = result.elements().items().get(2);
        assertThat(memory.passed()).isTrue();
        assertThat(memory.detail()).contains("会话记忆：已有有效多轮记录", "长期记忆：暂无用户记忆记录");
        assertThat(result.elements().missing()).doesNotContain("记忆");
        assertThat(result.elements().items().getFirst().passed()).isFalse();
    }

    @Test void declaredToolsWithoutCapabilitiesAreProvedByLoopAndToolObservations() throws Exception {
        var client = new LangfuseClient("", "", "") {
            @Override public JsonNode elementObservations(java.time.Instant from) {
                var body = json.createObjectNode();
                var rows = body.putArray("data");
                for (var id : java.util.List.of("1", "2")) {
                    var step = json.createObjectNode().put("traceId","t1").put("id",id).put("name","agent.loop.step").put("type","AGENT");
                    step.putObject("metadata").put("attributes.keel.agent","qa").put("attributes.keel.env","dev");
                    rows.add(step);
                }
                var tool = json.createObjectNode().put("traceId","t1").put("id","3").put("name","lookup").put("type","TOOL");
                tool.putObject("metadata").put("attributes.keel.agent","qa").put("attributes.keel.env","dev")
                        .put("attributes.langfuse.observation.type","tool");
                rows.add(tool);
                return body;
            }
        };
        var agent = new Agent(); agent.setName("qa");
        var version = new AgentVersion(); version.setEnv("dev"); version.setVersion("v1"); version.setManifestHash("hash");
        var manifest = json.readTree("""
                {"spec":{"kind":"workflow","tools":[{"name":"lookup"}],
                 "prompts":{"source":"langfuse","items":[{"name":"answer"}]}}}
                """);
        var result = new ElementEvidence(jdbc, client).report(AgentDetail.of(agent, version, null, List.of(), manifest, List.of()));
        assertThat(result.elements().items().get(0).passed()).isTrue();
        assertThat(result.elements().items().get(1).passed()).isTrue();
        assertThat(result.elements().missing()).doesNotContain("模型自己调工具", "agent loop");
    }

    @Test void userMemoryAndAbsentEvidenceAreReportedSeparately() throws Exception {
        var database = new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                return type.cast(sql.contains("layer='USER'") ? 1 : 0);
            }
        };
        var result = new ElementEvidence(database,new LangfuseClient("","","")).report(detail("hash","v1",false));
        assertThat(result.elements().items().get(2).passed()).isTrue();
        assertThat(result.elements().items().get(2).detail()).contains("会话记忆：暂无有效多轮记录", "长期记忆：已有用户记忆记录");
        var empty = new ElementEvidence(jdbc,new LangfuseClient("","","")).report(detail("hash","v1",false));
        assertThat(empty.elements().items().get(2).passed()).isNull();
        assertThat(empty.elements().missing()).doesNotContain("记忆");
        assertThat(ElementEvidence.needsRemote(empty.elements().items().get(2))).isFalse();
    }

    private AgentDetail detail(String hash, String number, boolean prompts) throws Exception {
        var agent = new Agent(); agent.setName("qa");
        var version = new AgentVersion(); version.setEnv("dev"); version.setVersion(number); version.setManifestHash(hash);
        var manifest = json.readTree(prompts ? "{\"spec\":{\"prompts\":{\"source\":\"langfuse\",\"items\":[{\"name\":\"role\"}]}}}" : "{\"spec\":{}}");
        return AgentDetail.of(agent,version,null,List.of(),manifest,List.of());
    }
}
