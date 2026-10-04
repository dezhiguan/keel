package com.keel.server.discovery;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Instance rows for heartbeat and probe. Does not create an agent. */
public class InstanceBook {
    private final JdbcTemplate jdbc;
    private final Set<String> known;
    private final List<Row> rows = new ArrayList<>();

    public InstanceBook(JdbcTemplate jdbc, Set<String> known) {
        this.jdbc = jdbc;
        this.known = known;
    }

    public void beat(String agent, String env, String instanceId, String version, Instant now) {
        if (!known(agent)) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        upsert(agent, env, instanceId, "heartbeat", version, true, now, true);
    }

    public void probe(String agent, String env, String instanceId, boolean up, Instant now) {
        if (!known(agent)) {
            return;
        }
        var current = find(agent, env, instanceId);
        var version = current == null ? null : current.version;
        var seen = up ? now : (current == null ? now : current.lastSeen);
        upsert(agent, env, instanceId, "probe", version, up, seen, up);
    }

    public void replaceVersion(String agent, String env, String instanceId, String version) {
        var current = find(agent, env, instanceId);
        if (current == null || version == null || version.isBlank()) {
            return;
        }
        current.version = version;
        if (jdbc != null) {
            jdbc.update("""
                    UPDATE agent_instance SET version = ?
                    WHERE agent_name = ? AND env = ? AND instance_id = ?
                    """, version, agent, env, instanceId);
        }
    }

    public void expire(Instant now) {
        for (var row : rows) {
            if (row.lastSeen.plus(ReconcileRules.HEARTBEAT).isBefore(now)) {
                row.ready = false;
            }
        }
        if (jdbc != null) {
            jdbc.update("""
                    UPDATE agent_instance SET ready = false
                    WHERE last_seen_at < ?
                    """, java.sql.Timestamp.from(now.minus(ReconcileRules.HEARTBEAT)));
        }
    }

    public Row find(String agent, String env, String instanceId) {
        return rows.stream()
                .filter(row -> row.agent.equals(agent) && row.env.equals(env) && row.instanceId.equals(instanceId))
                .findFirst()
                .orElse(null);
    }

    private void upsert(String agent, String env, String instanceId, String source, String version, boolean ready, Instant seen, boolean writeSeen) {
        var current = find(agent, env, instanceId);
        if (current == null) {
            current = new Row(agent, env, instanceId, source, version, ready, seen);
            rows.add(current);
        } else {
            current.source = source;
            current.ready = ready;
            if (version != null) {
                current.version = version;
            }
            if (writeSeen) {
                current.lastSeen = seen;
            }
        }
        if (jdbc != null) {
            jdbc.update("""
                    INSERT INTO agent_instance (agent_name, env, instance_id, source, version, ready, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (agent_name, env, instance_id)
                    DO UPDATE SET source = EXCLUDED.source, version = COALESCE(EXCLUDED.version, agent_instance.version),
                                  ready = EXCLUDED.ready, last_seen_at = EXCLUDED.last_seen_at
                    """, agent, env, instanceId, source, version, ready, java.sql.Timestamp.from(seen));
        }
    }

    private boolean known(String agent) {
        if (known != null) {
            return known.contains(agent);
        }
        if (jdbc == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM agent WHERE name = ?", Integer.class, agent);
        return count != null && count > 0;
    }

    public static final class Row {
        private final String agent;
        private final String env;
        private final String instanceId;
        private String source;
        private String version;
        private boolean ready;
        private Instant lastSeen;

        Row(String agent, String env, String instanceId, String source, String version, boolean ready, Instant lastSeen) {
            this.agent = agent;
            this.env = env;
            this.instanceId = instanceId;
            this.source = source;
            this.version = version;
            this.ready = ready;
            this.lastSeen = lastSeen;
        }

        public String source() { return source; }
        public String version() { return version; }
        public boolean ready() { return ready; }
        public Instant lastSeen() { return lastSeen; }
    }
}
