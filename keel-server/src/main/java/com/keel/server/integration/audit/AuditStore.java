package com.keel.server.integration.audit;

import java.util.Map;

/** Append-only audit rows the console can read when keel-audit is not on this process. */
public interface AuditStore {
    void append(String agent, String env, String action, String risk, String decision, String resource, String traceId);

    default void append(String agent, String env, String action, String risk, String decision, String resource,
                        String traceId, String actor, Map<String, Object> payload) {
        append(agent, env, action, risk, decision, resource, traceId);
    }

    Map<String, Object> page(String agent, String risk, String env, int page, int size);

    Map<String, Object> verify(String agent);
}
