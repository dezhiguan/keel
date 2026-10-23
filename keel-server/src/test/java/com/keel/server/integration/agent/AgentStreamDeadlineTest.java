package com.keel.server.integration.agent;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AgentStreamDeadlineTest {
    @Test void stalledBodyClosesAtDeadlineWithoutInterruptingReceiptThread() throws Exception {
        var closed=new CountDownLatch(1);
        var stalled=new InputStream() {
            @Override public int read() throws IOException {
                try { if(!closed.await(2,TimeUnit.SECONDS)) throw new IOException("deadline did not close body"); }
                catch(InterruptedException error) {throw new IOException(error);}
                throw new IOException("body closed");
            }
            @Override public void close() {closed.countDown();}
        };
        try(var input=AgentEndpointClient.boundedStream(stalled,Duration.ofMillis(50))) {
            var start=System.nanoTime();
            assertThatThrownBy(input::read).isInstanceOf(IOException.class).hasMessage("body closed");
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }
    }
}
