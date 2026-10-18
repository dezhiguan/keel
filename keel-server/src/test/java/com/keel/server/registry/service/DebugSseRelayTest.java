package com.keel.server.registry.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DebugSseRelayTest {
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

    @Test void returnsNullWhenTheStreamEndsWithoutFinal() throws Exception {
        var raw = "event: error\ndata: {\"message\":\"失败\"}\n\n";
        var fin = DebugSseRelay.copy(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        assertThat(fin).isNull();
    }
}
