package com.keel.server.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryEvidenceTest {
    @Test void flatAndNestedEvidenceIsExposedWithoutUnlistedFields() throws Exception {
        var json=new ObjectMapper();
        for(var shape:List.of("attributes.keel.memory.evidence","keel.memory.evidence")) {
            var row=json.createObjectNode();row.put("id","fact");row.put("name","memory.facts");
            row.put("startTime","2026-10-23T00:00:00Z");row.put("endTime","2026-10-23T00:00:00.010Z");
            var metadata=row.putObject("metadata");metadata.put("keel.agent","a");
            metadata.put("langfuse.observation.type","retriever");
            metadata.put(shape,"{\"operation\":\"facts\",\"version\":2,\"sourceIds\":[\"17\"],\"pending\":2,\"rawText\":\"MUST_NOT_LEAK\"}");
            var detail=TraceAssembly.detail("trace",List.of(row),"",Map.of());
            var nodes=(List<Map<String,Object>>)detail.get("nodes");
            var evidence=(Map<String,String>)nodes.getFirst().get("memoryEvidence");
            assertThat(evidence).containsEntry("version","2").containsEntry("pending","2").containsEntry("sourceIds","[\"17\"]");
            assertThat(evidence).doesNotContainKey("rawText");
        }
    }
}
