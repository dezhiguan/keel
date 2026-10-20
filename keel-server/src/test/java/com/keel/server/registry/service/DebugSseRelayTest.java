package com.keel.server.registry.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DebugSseRelayTest {
    @Test void taintedCallsStillStreamButCannotBeWrittenIntoSessionHistory() throws Exception {
        var raw = "event: guardrail\ndata: {\"hit\":true,\"mode\":\"shadow\"}\n\nevent: final\ndata: {\"answer\":\"safe refusal\",\"trace_id\":\"tr_1\",\"run_id\":\"r\"}\n\n";
        var out = new ByteArrayOutputStream();
        var fin = DebugSseRelay.copy(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), out);
        assertThat(fin.answer()).isEqualTo("safe refusal");
        assertThat(fin.rememberable()).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("event: guardrail").contains("event: final");
    }

    @Test void keepsTheFinalAnswerAndForwardsEveryFrame() throws Exception {
        var raw = """
                event: token
                data: {"text":"你"}

                event: final
                data: {"answer":"你好","trace_id":"tr_1","run_id":"r"}

                """;
        var out = new ByteArrayOutputStream();
        var fin = DebugSseRelay.copy(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), out);
        assertThat(fin.answer()).isEqualTo("你好");
        assertThat(fin.traceId()).isEqualTo("tr_1");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("event: token").contains("你好");
    }

    @Test void suspendedRepliesAreValidTerminalsWithoutInventingFinalOrPersistingResumeCredentials() throws Exception {
        var raw = "event: suspend\ndata: {\"prompt\":\"请确认需求\",\"trace_id\":\"tr_wait\",\"resume_token\":\"test-resume-token\"}\n\n";
        var out = new ByteArrayOutputStream();
        var reply = DebugSseRelay.copy(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),out);
        assertThat(reply.answer()).isEqualTo("请确认需求");
        assertThat(reply.traceId()).isEqualTo("tr_wait");
        assertThat(reply.rememberable()).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("event: suspend").doesNotContain("event: final");
    }

    @Test void returnsNullWhenTheStreamEndsWithoutFinal() throws Exception {
        var raw = "event: error\ndata: {\"message\":\"失败\"}\n\n";
        var fin = DebugSseRelay.copy(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        assertThat(fin).isNull();
    }
}
