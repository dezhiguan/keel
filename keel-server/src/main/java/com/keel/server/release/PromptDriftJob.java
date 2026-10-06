package com.keel.server.release;

import com.keel.server.integration.audit.AuditStore;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.prompt.PromptCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Alerts when staging or production labels move outside Keel. dev and test are not checked. */
@Component
public class PromptDriftJob {
    private static final Logger log = LoggerFactory.getLogger(PromptDriftJob.class);

    private final PromptCatalog catalog;
    private final LangfuseClient langfuse;
    private final JdbcTemplate jdbc;
    private final AuditStore audit;

    public PromptDriftJob(PromptCatalog catalog, LangfuseClient langfuse, JdbcTemplate jdbc, AuditStore audit) {
        this.catalog = catalog;
        this.langfuse = langfuse;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Scheduled(fixedRate = 600_000, initialDelay = 600_000)
    public void scheduled() {
        scan();
    }

    public int scan() {
        if (!langfuse.ready()) {
            return 0;
        }
        var production = namesWithLabel("production");
        var staging = namesWithLabel("staging");
        var written = 0;
        for (var item : catalog.declared(null)) {
            written += compare(item.agent(), item.name(), item.fullName(), "prod", "production", production, releaseVersion(item.agent(), item.name()));
            written += compare(item.agent(), item.name(), item.fullName(), "staging", "staging", staging, promotionVersion(item.agent(), item.name()));
        }
        return written;
    }

    private int compare(String agent, String prompt, String fullName, String env, String label, Set<String> listed, Integer expected) {
        Integer actual = null;
        if (listed.contains(fullName)) {
            try {
                var body = langfuse.getPrompt(fullName, null, label);
                if (body != null && body.path("version").canConvertToInt()) {
                    actual = body.path("version").asInt();
                }
            } catch (RuntimeException e) {
                log.warn("prompt drift read failed trace_id=- agent={} prompt={} env={}", agent, prompt, env);
                return 0;
            }
        }
        if ((expected == null && actual == null) || (expected != null && expected.equals(actual))) {
            return 0;
        }
        if (alreadyAlerted(agent, fullName, env, actual)) {
            return 0;
        }
        var version = actual == null ? -1 : actual;
        audit.append(agent, env, "config.change", "mid", "allowed", "prompt:" + fullName, null, "keel",
                Map.of("kind", "drift", "version", version));
        log.warn("prompt drift trace_id=- agent={} prompt={} env={} version={}", agent, prompt, env, version);
        return 1;
    }

    private boolean alreadyAlerted(String agent, String fullName, String env, Integer actual) {
        var rows = jdbc.query("""
                SELECT payload::text FROM console_audit_event
                WHERE agent = ? AND env = ? AND action = 'config.change' AND resource = ?
                ORDER BY ts DESC, event_id DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), agent, env, "prompt:" + fullName);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return false;
        }
        var payload = rows.getFirst();
        if (!payload.contains("\"kind\":\"drift\"") && !payload.contains("\"kind\": \"drift\"")) {
            return false;
        }
        var version = actual == null ? "-1" : String.valueOf(actual);
        return payload.contains("\"version\":" + version + ",")
                || payload.contains("\"version\": " + version + ",")
                || payload.contains("\"version\":" + version + "}")
                || payload.contains("\"version\": " + version + "}");
    }

    private Set<String> namesWithLabel(String label) {
        var names = new HashSet<String>();
        try {
            langfuse.listPrompts(null, label).path("data").forEach(row -> names.add(row.path("name").asText("")));
        } catch (RuntimeException e) {
            log.warn("prompt drift list failed trace_id=- agent=- label={}", label);
        }
        return names;
    }

    private Integer promotionVersion(String agent, String prompt) {
        var rows = jdbc.query("""
                SELECT version FROM prompt_promotion
                WHERE agent_name = ? AND prompt_name = ?
                ORDER BY promoted_at DESC, id DESC LIMIT 1
                """, (rs, n) -> rs.getInt(1), agent, prompt);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Integer releaseVersion(String agent, String prompt) {
        var rows = jdbc.query("""
                SELECT prompt_versions_json::text FROM release_record
                WHERE agent_name = ? AND env = 'prod' AND prompt_versions_json IS NOT NULL
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), agent);
        if (rows.isEmpty() || rows.getFirst() == null || !rows.getFirst().contains("\"" + prompt + "\"")) {
            return null;
        }
        var raw = rows.getFirst();
        var versionAt = raw.indexOf("\"version\"", raw.indexOf("\"" + prompt + "\""));
        if (versionAt < 0) {
            return null;
        }
        var digits = raw.substring(versionAt).replaceAll(".*?(-?\\d+).*", "$1");
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
