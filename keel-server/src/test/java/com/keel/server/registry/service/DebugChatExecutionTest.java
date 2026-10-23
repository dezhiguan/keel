package com.keel.server.registry.service;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class DebugChatExecutionTest {
    @Test void servletCancellationCannotInterruptPaidExecutionAndReceiptCleanup() throws Exception {
        var started=new CountDownLatch(1); var finish=new CountDownLatch(1); var settled=new CountDownLatch(1);
        var interrupted=new AtomicBoolean(); var receipt=new AtomicReference<String>(); var calls=new AtomicInteger();
        var waiter=Thread.ofVirtual().start(()->DebugChatExecution.run(()->{
            calls.incrementAndGet(); started.countDown();
            try {
                finish.await();
                var broken=new OutputStream(){public void write(int b) throws IOException {throw new IOException("client gone");}};
                try(var output=new DebugStreamOutput(broken,60_000)) {
                    output.write("event: final\ndata: {\"answer\":\"63\",\"trace_id\":\"original\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    receipt.set(output.terminal());
                }
            } catch (InterruptedException error) { interrupted.set(true); }
            catch (IOException error) { throw new UncheckedIOException(error); }
            finally { interrupted.compareAndSet(false,Thread.currentThread().isInterrupted()); settled.countDown(); }
        }));
        try {
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            waiter.interrupt(); waiter.join(2000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(settled.getCount()).isEqualTo(1);
            finish.countDown();
            assertThat(settled.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isFalse(); assertThat(calls).hasValue(1);
            assertThat(receipt.get()).contains("\"trace_id\":\"original\"");
        } finally { finish.countDown(); }
    }
}
