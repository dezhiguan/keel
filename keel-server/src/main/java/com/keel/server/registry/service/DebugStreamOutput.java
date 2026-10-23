package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Serialized SSE writes, proxy keepalive, and bounded capture of the original safe terminal. */
public final class DebugStreamOutput extends OutputStream implements AutoCloseable {
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("debug-sse-heartbeat").factory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private final OutputStream target;
    private final ScheduledFuture<?> heartbeat;
    private final java.util.concurrent.atomic.AtomicBoolean heartbeatActive = new java.util.concurrent.atomic.AtomicBoolean();
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
    private String terminal;
    private boolean disconnected;
    private boolean overflow;

    public DebugStreamOutput(OutputStream target) { this(target, 15_000); }

    DebugStreamOutput(OutputStream target, long intervalMillis) {
        this.target = target;
        heartbeat = HEARTBEATS.scheduleAtFixedRate(()->{
            // One slow connection must not block every other stream's timer.
            if (heartbeatActive.compareAndSet(false,true)) Thread.startVirtualThread(()->{
                try { keepalive(); } finally { heartbeatActive.set(false); }
            });
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void keepalive() {
        if (disconnected) return;
        try {
            // No blank line: safe even between a frame's event: and data: lines.
            target.write(": keepalive\n".getBytes(StandardCharsets.UTF_8));
            target.flush();
        } catch (IOException error) { disconnected = true; }
    }

    @Override public synchronized void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }

    @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (terminal != null) return; // Never replace an already completed reply with a later relay error.
        // Capture before writing: a completed upstream terminal can be replayed if its delivery fails.
        for (int i=offset; i<offset+length; i++) {
            int value=bytes[i]&255;
            if (!overflow) {
                frame.write(value);
                if (frame.size()>MAX_FRAME) { frame.reset(); overflow=true; }
                else if (value=='\n') {
                    var raw=frame.toByteArray();
                    if (raw.length>=2 && raw[raw.length-2]=='\n') { remember(frame.toString(StandardCharsets.UTF_8)); frame.reset(); }
                }
            }
        }
        // Stopping receipt does not restart/cancel a paid invocation. Finish its
        // original bounded execution and retain the receipt for a retry.
        if (disconnected) return;
        try { target.write(bytes,offset,length); }
        catch (IOException error) { disconnected=true; }
    }

    private void remember(String raw) {
        String event=""; var data=new StringBuilder();
        for (var line:raw.split("\n")) {
            if (line.startsWith("event:")) event=line.substring(6).trim();
            else if (line.startsWith("data:")) data.append(line.substring(5).trim()).append('\n');
        }
        if (!event.equals("final") && !event.equals("error")) return; // Never cache resume credentials.
        try {
            var body=JSON.readTree(data.toString());
            if (body==null || !body.isObject() || body.path("trace_id").asText().isBlank()) return;
            if (event.equals("final") && !body.path("answer").isTextual()) return;
            if (event.equals("error") && !body.path("code").isTextual()) return;
            terminal="event: "+event+"\ndata: "+data.toString().trim()+"\n\n";
        } catch (IOException ignored) { /* A malformed result is unknown, never a successful receipt. */ }
    }

    public synchronized String terminal() { return terminal; }
    @Override public synchronized void flush() throws IOException {
        if (disconnected) return;
        try { target.flush(); } catch (IOException error) { disconnected=true; }
    }
    @Override public void close() { heartbeat.cancel(false); } // Servlet owns target lifetime.
}
