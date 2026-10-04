package com.keel.server.insight;

import java.util.List;
import java.util.Map;

public interface SavedTraces {
    SavedTraces EMPTY = new SavedTraces() {
        @Override
        public void save(String agent, String env, String traceId, String question, int durationMs) {
        }

        @Override
        public Map<String, Object> list(int page, int size, String agent) {
            return Map.of("page", page, "size", size, "total", 0, "items", List.of());
        }

        @Override
        public Map<String, Object> detail(String traceId) {
            return null;
        }
    };

    void save(String agent, String env, String traceId, String question, int durationMs);

    Map<String, Object> list(int page, int size, String agent);

    Map<String, Object> detail(String traceId);
}
