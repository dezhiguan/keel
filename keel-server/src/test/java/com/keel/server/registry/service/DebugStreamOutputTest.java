package com.keel.server.registry.service;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class DebugStreamOutputTest {
    private static final String FINAL="event: final\ndata: {\"answer\":\"你好\",\"trace_id\":\"trace\",\"run_id\":\"run\",\"meta\":{\"prompt_versions\":{\"answer\":13}}}\n\n";

    @Test void keepaliveDoesNotTerminateOrChangeAnInProgressEvent() throws Exception {
        var target=new ByteArrayOutputStream(); var arrived=new CountDownLatch(1);
        var client=new OutputStream() {
            @Override public void write(int b) { target.write(b); }
            @Override public void write(byte[] b,int off,int len) {
                target.write(b,off,len);
                if(new String(b,off,len,StandardCharsets.UTF_8).startsWith(": keepalive")) arrived.countDown();
            }
        };
        try(var output=new DebugStreamOutput(client,10)) {
            output.write("event: final\n".getBytes(StandardCharsets.UTF_8));
            assertThat(arrived.await(2,TimeUnit.SECONDS)).isTrue();
            output.write(FINAL.substring(FINAL.indexOf("data:")).getBytes(StandardCharsets.UTF_8));
            assertThat(output.terminal()).isEqualTo(FINAL);
        }
        var fin=DebugSseRelay.copy(new ByteArrayInputStream(target.toByteArray()),new ByteArrayOutputStream());
        assertThat(fin.answer()).isEqualTo("你好");
    }

    @Test void disconnectedClientDoesNotLoseTheOriginalPaidResultOrStartAnotherInvocation() throws Exception {
        var broken=new OutputStream(){ @Override public void write(int b) throws IOException {throw new IOException("closed");}};
        try(var output=new DebugStreamOutput(broken,60_000)) {
            var fin=DebugSseRelay.copy(new ByteArrayInputStream(FINAL.getBytes(StandardCharsets.UTF_8)),output);
            assertThat(fin.answer()).isEqualTo("你好");
            assertThat(output.terminal()).isEqualTo(FINAL);
        }
    }

    @Test void suspensionCredentialsAreNeverCachedAndErrorIsReplayable() throws Exception {
        try(var output=new DebugStreamOutput(new ByteArrayOutputStream(),60_000)) {
            output.write("event: suspend\ndata: {\"trace_id\":\"t\",\"resume_token\":\"credential\"}\n\n".getBytes(StandardCharsets.UTF_8));
            assertThat(output.terminal()).isNull();
            var error="event: error\ndata: {\"code\":\"SERVER_INTERNAL_ERROR\",\"trace_id\":\"t\",\"message\":\"超时\"}\n\n";
            output.write(error.getBytes(StandardCharsets.UTF_8));
            assertThat(output.terminal()).isEqualTo(error);
        }
    }

    @Test void oneTerminalIsDeliveredEvenIfPostReplyBookkeepingFails() throws Exception {
        var target=new ByteArrayOutputStream();
        try(var output=new DebugStreamOutput(target,60_000)) {
            output.write(FINAL.getBytes(StandardCharsets.UTF_8));
            output.write("event: error\ndata: {\"code\":\"SERVER_INTERNAL_ERROR\",\"trace_id\":\"later\"}\n\n".getBytes(StandardCharsets.UTF_8));
            assertThat(output.terminal()).isEqualTo(FINAL);
        }
        assertThat(target.toString(StandardCharsets.UTF_8)).isEqualTo(FINAL);
    }
}
