package com.keel.starter.context;

import com.keel.common.model.FinalEvent;
import com.keel.common.model.StepEvent;
import com.keel.common.model.SuspendEvent;
import com.keel.common.model.TokenEvent;
import com.keel.common.model.ToolEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Per-invocation context. Not a Spring singleton: two agents in one process each get their own.
 * {@code step}/{@code token}/{@code tool} are written to the SSE stream immediately.
 */
public final class KeelContext {
    private final String agent;
    private final String runId;
    private final String traceId;
    private final BiConsumer<String, Object> emit;

    public KeelContext(String agent, String runId, String traceId, BiConsumer<String, Object> emit) {
        this.agent = agent;
        this.runId = runId;
        this.traceId = traceId;
        this.emit = emit;
    }

    public String agent() { return agent; }

    public String runId() { return runId; }

    public String traceId() { return traceId; }

    public void step(String name) {
        step(name, StepEvent.StepEventStatusValue.OK);
    }

    public void step(String name, StepEvent.StepEventStatusValue status) {
        emit.accept("step", new StepEvent(name, status));
    }

    public void token(String text) {
        emit.accept("token", new TokenEvent(text));
    }

    public void tool(String name) {
        tool(name, ToolEvent.ToolEventStatusValue.OK);
    }

    public void tool(String name, ToolEvent.ToolEventStatusValue status) {
        emit.accept("tool", new ToolEvent(name, status));
    }

    /** Java cannot name this {@code final}; it is the Python {@code ctx.final}. */
    public FinalEvent finish(String answer) {
        return new FinalEvent(answer, List.of(), Map.of(), traceId, runId);
    }

    public SuspendEvent suspend(SuspendEvent.SuspendEventReasonValue reason, String ref,
                                String prompt, Instant deadline) {
        String token = UUID.randomUUID().toString().replace("-", "");
        return new SuspendEvent(runId, reason, ref, prompt, deadline, token, traceId);
    }
}
