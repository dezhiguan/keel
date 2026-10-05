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

    Map<String, Object> detail(String traceId);

    /** Invocations at or after {@code from}. {@code env} is {@code all} or a single environment. */
    default List<TraceHit> since(Instant from, String env) {
        return List.of();
    }

    record TraceHit(String traceId, String agent) {}
}
