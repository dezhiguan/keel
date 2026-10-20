package com.keel.server.registry.service;

import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.dto.AgentElements;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Recent sample evidence is independent of the first paint and of release gates. */
@Service
public class ElementEvidence {
    private static final Set<String> REMOTE = Set.of("模型自己调工具", "agent loop", "记忆", "有版本的提示词", "知识检索");
    private final JdbcTemplate jdbc;
    private final LangfuseClient langfuse;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    public ElementEvidence(JdbcTemplate jdbc, LangfuseClient langfuse) { this.jdbc = jdbc; this.langfuse = langfuse; }

    public AgentDetail apply(AgentDetail detail) { return detail.withElements(report(detail).elements()); }

    public Report report(AgentDetail detail) {
        var agent = detail.summary().name();
        var declared = detail.elements();
        if (declared == null) return new Report(null, detail.manifestHash(), Instant.now().toString(), List.of());
        var local = declared.withRuntime(false, false, count("SELECT count(*) FROM user_memory WHERE agent_name=?", agent) > 0,
                count("SELECT count(*) FROM eval_run WHERE agent_name=? AND state='DONE' AND result->>'passed'='true'", agent) > 0,
                count("SELECT count(*) FROM console_audit_event WHERE agent=?", agent) > 0);
        var pending = local.items().stream().filter(i -> needsRemote(i)).map(AgentElements.Item::name).toList();
        if (pending.isEmpty()) return new Report(local, detail.manifestHash(), Instant.now().toString(), List.of());
        var env = detail.summary().env();
        var key = agent + "@" + env + "@" + detail.summary().version() + "@" + detail.manifestHash();
        var sample = cache.compute(key, (ignored, previous) -> {
            if (previous != null && previous.until().isAfter(Instant.now())) return previous;
            var at = Instant.now();
            try {
                var evidence = ElementObservations.read(langfuse.elementObservations(at.minus(Duration.ofDays(7))), agent, env);
                return new Cached(evidence, true, at, at.plus(Duration.ofMinutes(5)));
            } catch (RuntimeException error) {
                return new Cached(new ElementObservations.Evidence(false,false,false,false,false), false, at, at.plusSeconds(15));
            }
        });
        if (cache.size() > 1000) cache.entrySet().removeIf(e -> e.getValue().until().isBefore(Instant.now()));
        var evidence = sample.evidence();
        var proved = local.withRuntime(evidence.tool(), evidence.loop(), evidence.session(), false, false,
                evidence.prompt(), evidence.retrieval());
        return new Report(proved, detail.manifestHash(), sample.at().toString(), sample.available() ? List.of() : pending);
    }

    static boolean needsRemote(AgentElements.Item item) {
        return REMOTE.contains(item.name()) && item.passed() == null && item.detail() != null && !item.detail().contains("未声明");
    }
    private int count(String sql, String agent) { Integer value = jdbc.queryForObject(sql,Integer.class,agent); return value == null ? 0 : value; }
    private record Cached(ElementObservations.Evidence evidence, boolean available, Instant at, Instant until) {}
    public record Report(AgentElements elements, String manifestHash, String checkedAt, List<String> unavailable) {}
}
