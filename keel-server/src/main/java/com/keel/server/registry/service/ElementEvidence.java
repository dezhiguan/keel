package com.keel.server.registry.service;

import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.dto.AgentElements;
import com.keel.server.insight.TraceQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads records that already exist. It does not start a run or invent a pass. */
@Service
public class ElementEvidence {
    private static final Logger log = LoggerFactory.getLogger(ElementEvidence.class);
    private static final java.time.Duration TRACE_TTL = java.time.Duration.ofMinutes(1);
    private final JdbcTemplate jdbc;
    private final TraceQueryService traces;
    private final java.util.concurrent.ConcurrentHashMap<String, CachedEvidence> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public ElementEvidence(JdbcTemplate jdbc, TraceQueryService traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    public AgentDetail apply(AgentDetail detail) {
        if (detail == null || detail.elements() == null || detail.summary() == null || detail.summary().name() == null) {
            return detail;
        }
        var agent = detail.summary().name();
        var evidence = traceEvidence(agent, detail.summary().env());
        var next = detail.elements().withRuntime(
                evidence.toolCall(),
                evidence.loop(),
                evidence.sessionMemory() || count("SELECT count(*) FROM user_memory WHERE agent_name=?", agent) > 0,
                count("SELECT count(*) FROM eval_run WHERE agent_name=? AND state='DONE' AND result->>'passed'='true'", agent) > 0,
                count("SELECT count(*) FROM console_audit_event WHERE agent=?", agent) > 0,
                evidence.prompt(), evidence.retrieval());
        return detail.withElements(next);
    }

    private TraceEvidence traceEvidence(String agent, String env) {
        if (traces == null || env == null || env.isBlank()) return new TraceEvidence(false, false, false, false, false);
        var key = agent + "@" + env;
        var cached = cache.get(key);
        if (cached != null && cached.at().plus(TRACE_TTL).isAfter(java.time.Instant.now())) return cached.evidence();
        var evidence = readTraceEvidence(agent, env);
        cache.put(key, new CachedEvidence(evidence, java.time.Instant.now()));
        return evidence;
    }

    private TraceEvidence readTraceEvidence(String agent, String env) {
        try {
            var page = traces.list(1, 20, agent, env);
            if (!(page.get("items") instanceof java.util.List<?> items)) return new TraceEvidence(false, false, false, false, false);
            var sessions = new java.util.HashSet<String>();
            boolean sessionMemory = false;
            for (var item : items) {
                if (!(item instanceof java.util.Map<?, ?> row)) continue;
                var session = row.get("sessionId");
                if (session instanceof String id && !id.isBlank() && !sessions.add(id)) sessionMemory = true;
            }
            boolean prompt = false;
            boolean retrieval = false;
            boolean loop = false;
            boolean toolCall = false;
            int checked = 0;
            for (var item : items) {
                if (checked++ >= 8) break;
                if (!(item instanceof java.util.Map<?, ?> row)) continue;
                var id = row.get("traceId");
                if (!(id instanceof String traceId) || traceId.isBlank()) continue;
                java.util.Map<String, Object> trace;
                try {
                    trace = traces.detail(traceId);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (trace == null) continue;
                if (!(trace.get("nodes") instanceof java.util.List<?> nodes)) continue;
                int steps = 0;
                boolean tool = false;
                for (var node : nodes) {
                    if (!(node instanceof java.util.Map<?, ?> span)) continue;
                    if ("agent.loop.step".equals(span.get("name"))) steps++;
                    tool |= "tool".equals(span.get("type"));
                    prompt |= "generation".equals(span.get("type"))
                            && span.get("promptName") instanceof String name && !name.isBlank()
                            && span.get("promptVersion") instanceof Number;
                    retrieval |= "retriever".equals(span.get("type"));
                }
                loop |= steps > 1;
                toolCall |= steps > 1 && tool;
                if (prompt && retrieval && loop && toolCall) break;
            }
            return new TraceEvidence(prompt, retrieval, loop, toolCall, sessionMemory);
        } catch (RuntimeException error) {
            log.debug("Cannot read element trace evidence for {}", agent, error);
            return new TraceEvidence(false, false, false, false, false);
        }
    }

    private record TraceEvidence(boolean prompt, boolean retrieval, boolean loop, boolean toolCall, boolean sessionMemory) {}
    private record CachedEvidence(TraceEvidence evidence, java.time.Instant at) {}

    private int count(String sql, String agent) {
        Integer value = jdbc.queryForObject(sql, Integer.class, agent);
        return value == null ? 0 : value;
    }
}
