package com.keel.server.devflow;

import com.keel.server.integration.audit.AuditStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class RecordingAudit implements AuditStore {
    final List<Map<String, Object>> payloads = new ArrayList<>();

    @Override
    public void append(String agent, String env, String action, String risk, String decision, String resource, String traceId) {
    }

    @Override
    public void append(String agent, String env, String action, String risk, String decision, String resource,
                       String traceId, String actor, Map<String, Object> payload) {
        payloads.add(payload);
    }

    @Override
    public Map<String, Object> page(String agent, String risk, String env, int page, int size) {
        return Map.of();
    }

    @Override
    public Map<String, Object> verify(String agent) {
        return Map.of();
    }
}
