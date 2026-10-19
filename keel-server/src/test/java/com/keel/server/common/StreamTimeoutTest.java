package com.keel.server.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import static org.assertj.core.api.Assertions.*;

class StreamTimeoutTest {
    @Test void timeoutOnAnExistingStreamIsAParseableErrorFrame() throws Exception {
        var servlet = new MockHttpServletResponse();
        servlet.setContentType("text/event-stream");
        var reply = new GlobalExceptionHandler().streamTimeout(new org.springframework.mock.web.MockHttpServletRequest(), servlet);
        assertThat(reply.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        var frame = (String) reply.getBody();
        assertThat(frame).startsWith("event: error\ndata: ").endsWith("\n\n");
        var payload = new ObjectMapper().readTree(frame.substring(frame.indexOf("data: ") + 6).trim());
        assertThat(payload.path("code").asText()).isEqualTo("SERVER_INTERNAL_ERROR");
        assertThat(payload.path("retryable").asBoolean()).isTrue();
        assertThat(payload.path("trace_id").asText()).isNotBlank();
    }

    @Test void ordinaryTimeoutRemainsJson() {
        var reply = new GlobalExceptionHandler().streamTimeout(new org.springframework.mock.web.MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(reply.getHeaders().getContentType().toString()).isEqualTo("application/json");
    }
}
