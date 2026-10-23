package com.keel.server.registry.service;

/** A Servlet disconnect cancels its async task. It must not interrupt the paid invocation or its receipt. */
public final class DebugChatExecution {
    private DebugChatExecution() {}

    @FunctionalInterface public interface Work { void run() throws java.io.IOException; }

    public static void run(Work execution) {
        var worker=Thread.ofVirtual().name("debug-chat-execution").start(() -> {
            try { execution.run(); }
            catch (java.io.IOException error) {
                org.slf4j.LoggerFactory.getLogger(DebugChatExecution.class).warn("chat execution output failed",error);
            }
        });
        try { worker.join(); }
        catch (InterruptedException disconnected) {
            // Container cancellation ends only the waiter; the original bounded invocation owns cleanup.
            Thread.currentThread().interrupt();
        }
    }
}
