package com.keel.server.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvaluatedReleaseTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void onlyCompletePassingResultsWithActualEvidenceCanPublish() throws Exception {
        var valid = json.readTree("{\"passed\":true,\"complete\":true,\"caseCount\":51,\"promptVersions\":{\"answer\":2}}");
        assertThatCode(() -> ReleaseService.requirePublishable("DONE", valid, "registry/image:commit")).doesNotThrowAnyException();
        for (var field : java.util.List.of("passed", "complete", "promptVersions")) {
            var invalid = valid.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).remove(field);
            assertThatThrownBy(() -> ReleaseService.requirePublishable("DONE", invalid, "registry/image:commit"))
                    .isInstanceOf(com.keel.server.common.KeelException.class);
        }
        assertThatThrownBy(() -> ReleaseService.requirePublishable("RUNNING", valid, "registry/image:commit"))
                .isInstanceOf(com.keel.server.common.KeelException.class);
        assertThatThrownBy(() -> ReleaseService.requirePublishable("DONE", valid, ""))
                .isInstanceOf(com.keel.server.common.KeelException.class);
    }
}
