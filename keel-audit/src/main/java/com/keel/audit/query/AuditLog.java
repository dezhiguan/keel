package com.keel.audit.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Queryable copy of events after masking. The chain store remains the hash source. */
public class AuditLog {
    private final List<JsonNode> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final com.keel.audit.chain.ChainStore store;
    public AuditLog() { this(null); }
    public AuditLog(com.keel.audit.chain.ChainStore store) { this.store = store; }

    private List<JsonNode> snapshot(String agent) {
        if (store == null) return List.copyOf(events);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        return store.list(agent == null ? "" : agent).stream().map(link -> {
            try {
                var event = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(link.canonicalJson());
                event.put("hash", link.hash());
                event.put("prevHash", link.prevHash());
                event.put("hashVerified", com.keel.audit.chain.CanonicalHash.sha256(link.canonicalJson(),
                        link.prevHash() == null ? "" : link.prevHash()).equals(link.hash())
                        && event.path("prev_hash").asText("").equals(link.prevHash() == null ? "" : link.prevHash()));
                return (JsonNode) event;
            } catch (java.io.IOException error) { throw new IllegalStateException("Invalid persisted audit event", error); }
        }).sorted(java.util.Comparator.comparing((JsonNode e) -> java.time.Instant.parse(e.path("ts").asText())).reversed()
                .thenComparing(e -> e.path("event_id").asText())).toList();
    }

    public void add(JsonNode event) {
        if (store == null) events.add(event);
    }

    public List<JsonNode> filter(String agent, String action, String risk, String traceId, Instant from, Instant to, int page, int size) {
        return filter(null, agent, action, risk, traceId, from, to, page, size);
    }

    public List<JsonNode> filter(String env, String agent, String action, String risk, String traceId, Instant from, Instant to, int page, int size) {
        var matched = snapshot(agent).stream().filter(event -> matches(event, env, agent, action, risk, traceId, from, to)).toList();
        int start = Math.max(0, (page - 1) * size);
        if (start >= matched.size()) {
            return List.of();
        }
        return matched.subList(start, Math.min(matched.size(), start + size));
    }

    public int total(String agent, String action, String risk, String traceId, Instant from, Instant to) {
        return total(null, agent, action, risk, traceId, from, to);
    }

    public int total(String env, String agent, String action, String risk, String traceId, Instant from, Instant to) {
        return (int) snapshot(agent).stream().filter(event -> matches(event, env, agent, action, risk, traceId, from, to)).count();
    }

    private static boolean matches(JsonNode event, String env, String agent, String action, String risk, String traceId, Instant from, Instant to) {
        if (env != null && !env.isBlank() && !"all".equals(env) && !env.equals(event.path("env").asText())) {
            return false;
        }
        if (agent != null && !agent.isBlank() && !agent.equals(event.path("agent").asText())) {
            return false;
        }
        if (action != null && !action.isBlank() && !action.equals(event.path("action").asText())) {
            return false;
        }
        if (risk != null && !risk.isBlank() && !risk.equals(event.path("risk").asText())) {
            return false;
        }
        if (traceId != null && !traceId.isBlank() && !traceId.equals(event.path("trace_id").asText())) {
            return false;
        }
        var ts = event.path("ts").asText("");
        if (!ts.isBlank()) {
            var instant = Instant.parse(ts);
            if (from != null && instant.isBefore(from)) {
                return false;
            }
            if (to != null && !instant.isBefore(to)) {
                return false;
            }
        }
        return true;
    }
}
