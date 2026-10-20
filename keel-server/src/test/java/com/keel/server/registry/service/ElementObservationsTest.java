package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ElementObservationsTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode row(String agent,String env,String trace,String id,String name,String type) {
        var row=json.createObjectNode().put("traceId",trace).put("id",id).put("name",name).put("type",type);
        row.putObject("metadata").put("attributes.keel.agent",agent).put("attributes.keel.env",env)
                .put("attributes.langfuse.session.id","session"); return row;
    }
    @Test void unrelatedAgentsAndEnvironmentsCannotSupplyLoopToolOrMemoryProof() {
        var body=json.createObjectNode();var rows=body.putArray("data");
        rows.add(row("qa","dev","trace1","1","agent.loop.step","AGENT"));
        rows.add(row("qa","dev","trace2","2","agent.loop.step","AGENT"));
        rows.add(row("other","dev","trace1","3","agent.loop.step","AGENT"));
        rows.add(row("qa","prod","trace1","4","agent.loop.step","AGENT"));
        rows.add(row("qa","dev","trace1","5","tool","TOOL"));
        var proof=ElementObservations.read(body,"qa","dev");
        assertThat(proof.loop()).isFalse();assertThat(proof.tool()).isFalse();
        assertThat(proof.session()).isTrue(); // Two different traces belonging to qa/dev.
        assertThat(ElementObservations.read(body,"qa","prod").session()).isFalse();
    }
    @Test void childGenerationsInheritOnlyTheirOwnUnambiguousAgentTraceEnvironment() {
        var body=json.createObjectNode();var rows=body.putArray("data");
        rows.add(row("qa","dev","trace","root","agent.run","AGENT"));
        var generation=json.createObjectNode().put("traceId","trace").put("type","GENERATION").put("environment","default");
        generation.putObject("metadata").put("attributes.keel.agent","qa")
                .put("attributes.langfuse.observation.prompt.name","qa/role")
                .put("attributes.langfuse.observation.prompt.version",1);
        rows.add(generation);
        assertThat(ElementObservations.read(body,"qa","dev").prompt()).isTrue();
        assertThat(ElementObservations.read(body,"qa","prod").prompt()).isFalse();
        assertThat(ElementObservations.read(body,"other","dev").prompt()).isFalse();
        rows.add(row("qa","prod","trace","other-env-root","agent.run","AGENT"));
        assertThat(ElementObservations.read(body,"qa","dev").prompt()).isFalse();
    }

    @Test void nestedMetadataAndFlatAttributesAreSupportedWithoutCountingDuplicateObservationsTwice() {
        var body=json.createObjectNode();var rows=body.putArray("data");
        var first=row("qa","dev","trace","1","agent.loop.step","AGENT");
        rows.add(first); rows.add(first.deepCopy());
        rows.add(row("qa","dev","trace","2","tool","TOOL"));
        assertThat(ElementObservations.read(body,"qa","dev").loop()).isFalse();
        rows.add(row("qa","dev","trace","3","agent.loop.step","AGENT"));
        var generation=json.createObjectNode().put("traceId","trace").put("type","GENERATION");
        generation.putObject("metadata").putObject("attributes").put("keel.agent","qa").put("keel.env","dev")
                .put("langfuse.observation.prompt.name","qa/role").put("langfuse.observation.prompt.version",1);
        rows.add(generation);
        var proof=ElementObservations.read(body,"qa","dev");
        assertThat(proof.loop()).isTrue();assertThat(proof.tool()).isTrue();assertThat(proof.prompt()).isTrue();
        assertThat(proof.session()).isFalse(); // Multiple spans are still a single run.
    }
}
