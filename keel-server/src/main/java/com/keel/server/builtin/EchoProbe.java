package com.keel.server.builtin;

import com.keel.server.insight.SavedTraces;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** One-step agent: records an invoke audit and a trace, then returns a final SSE event. */
@Service
public class EchoProbe {
    static final String QUESTION = "探针";

    private final SavedTraces traces;
    private final AuditStore audit;

    public EchoProbe(SavedTraces traces, AuditStore audit) {
        this.traces = traces;
        this.audit = audit;
    }

    public String invoke(String agent, String env) {
        var traceId = UUID.randomUUID().toString().replace("-", "");
        traces.save(agent, env, traceId, QUESTION, 1);
        audit.append(agent, env, "invoke", "low", "allowed", agent, traceId);
        return "event: final\ndata: {}\n\n";
    }
}
