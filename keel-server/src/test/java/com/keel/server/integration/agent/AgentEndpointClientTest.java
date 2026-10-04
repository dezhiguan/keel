package com.keel.server.integration.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEndpointClientTest {
    @Test void readsTheFinalAnswerAndTraceId() {
        var answer = AgentEndpointClient.parseFinal("""
                event: step
                data: {"name":"llm.chat"}

                event: final
                data: {"answer":"你好","trace_id":"abc"}
                """);
        assertThat(answer.text()).isEqualTo("你好");
        assertThat(answer.traceId()).isEqualTo("abc");
    }

    @Test void rejectsAnErrorEvent() {
        assertThatThrownBy(() -> AgentEndpointClient.parseFinal("event: error\ndata: {\"code\":\"SERVER_INTERNAL_ERROR\"}\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVER_INTERNAL_ERROR");
    }
}
