package com.keel.server.registry.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StreamRelayErrorTest {
    @Test void interruptedStreamHasRequiredContractIdentifiersAndNeverClaimsAgentCompletion() throws Exception {
        var frame=AgentController.streamError("f".repeat(32),"console-relay-test");
        assertThat(frame).startsWith("event: error\n").endsWith("\n\n").doesNotContain("event: final");
        var body=new ObjectMapper().readTree(frame.split("data: ",2)[1].trim());
        assertThat(body.path("trace_id").asText()).hasSize(32);
        assertThat(body.path("run_id").asText()).startsWith("console-relay-");
        assertThat(body.path("code").asText()).isEqualTo(ErrorCode.SERVER_INTERNAL_ERROR.name());
        assertThat(body.path("retryable").asBoolean()).isEqualTo(ErrorCode.SERVER_INTERNAL_ERROR.retryable());
    }
}
