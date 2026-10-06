package com.keel.server.insight;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface SavedTraces {
    SavedTraces EMPTY = new SavedTraces() {
        @Override
        public void save(String agent, String env, String traceId, String question, int durationMs) {
        }

        @Override
        public Map<String, Object> list(int page, int size, String agent) {
            return list(page, size, agent, "all");
        }

        @Override
        public Map<String, Object> list(int page, int size, String agent, String env) {
            return Map.of("page", page, "size", size, "total", 0, "items", List.of());
        }

        @Override
        public Map<String, Object> detail(String traceId) {
            return null;
        }

        @Override
        public List<TraceHit> since(Instant from, String env) {
            return List.of();
        }
    };

    void save(String agent, String env, String traceId, String question, int durationMs);

    Map<String, Object> list(int page, int size, String agent);

    /** {@code env} is {@code all} or a single environment. */
    default Map<String, Object> list(int page, int size, String agent, String env) {
        return list(page, size, agent);
    }

    /**
     * Same page, plus the list-page filters. The default ignores the extra filters so test doubles
     * that only implement {@link #list(int, int, String)} keep working.
     */
    default Map<String, Object> list(int page, int size, String agent, String env, String status,
                                     Instant from, Instant to, boolean multiOnly, Integer minDurationMs) {
        return list(page, size, agent, env);
    }

    /** Runs that are still waiting on a person. */
    default List<Map<String, Object>> suspended(String agent, String env) {
        return List.of();
    }

    /** Audit events, approval and an open suspend for one trace. Empty when nothing was recorded. */
    default TraceContext context(String traceId) {
        return TraceContext.EMPTY;
    }

    Map<String, Object> detail(String traceId);

    /** Invocations at or after {@code from}. {@code env} is {@code all} or a single environment. */
    default List<TraceHit> since(Instant from, String env) {
        return List.of();
    }

    record TraceHit(String traceId, String agent) {}

    record TraceContext(List<String> auditIds, String approvalId, String pendingReason, String runId,
                        Integer suspendCount, String userId, String sessionId, Long humanWaitMs) {
        static final TraceContext EMPTY = new TraceContext(List.of(), null, null, null, null, null, null, null);

        boolean empty() {
            return (auditIds == null || auditIds.isEmpty()) && approvalId == null && pendingReason == null
                    && runId == null && userId == null && sessionId == null && humanWaitMs == null;
        }
    }
}
