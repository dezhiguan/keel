package com.keel.server.discovery;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Open reconcile findings. The same agent, env, and kind stay on one unresolved row. */
public class FindingBook {
    private final JdbcTemplate jdbc;
    private final List<Row> rows = new ArrayList<>();

    public FindingBook(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        if (jdbc != null) {
            jdbc.query("""
                    SELECT agent_name, env, kind, detail::text, first_seen, resolved_at
                    FROM reconcile_finding WHERE resolved_at IS NULL
                    """, rs -> {
                rows.add(new Row(
                        rs.getString("agent_name"),
                        rs.getString("env"),
                        rs.getString("kind"),
                        rs.getString("detail"),
                        rs.getTimestamp("first_seen").toInstant(),
                        null));
            });
        }
    }

    public void sync(String agent, String env, String kind, String detail, Instant now) {
        var open = rows.stream()
                .filter(row -> row.agent.equals(agent) && row.env.equals(env) && row.resolvedAt == null)
                .toList();
        var kept = false;
        for (var row : open) {
            if (kind != null && row.kind.equals(kind)) {
                row.detail = detail;
                kept = true;
                if (jdbc != null) {
                    jdbc.update("""
                            UPDATE reconcile_finding SET detail = ?::jsonb
                            WHERE agent_name = ? AND env = ? AND kind = ? AND resolved_at IS NULL
                            """, detail, agent, env, kind);
                }
            } else {
                row.resolvedAt = now;
                if (jdbc != null) {
                    jdbc.update("""
                            UPDATE reconcile_finding SET resolved_at = ?
                            WHERE agent_name = ? AND env = ? AND kind = ? AND resolved_at IS NULL
                            """, java.sql.Timestamp.from(now), agent, env, row.kind);
                }
            }
        }
        if (kind != null && !kept) {
            rows.add(new Row(agent, env, kind, detail, now, null));
            if (jdbc != null) {
                jdbc.update("""
                        INSERT INTO reconcile_finding (agent_name, env, kind, detail, first_seen)
                        VALUES (?, ?, ?, ?::jsonb, ?)
                        """, agent, env, kind, detail, java.sql.Timestamp.from(now));
            }
        }
    }

    public List<Row> open(String agent, String env) {
        return rows.stream()
                .filter(row -> row.agent.equals(agent) && row.env.equals(env) && row.resolvedAt == null)
                .toList();
    }

    public List<Row> all(String agent, String env) {
        return rows.stream().filter(row -> row.agent.equals(agent) && row.env.equals(env)).toList();
    }

    public static final class Row {
        private final String agent;
        private final String env;
        private final String kind;
        private String detail;
        private final Instant firstSeen;
        private Instant resolvedAt;

        Row(String agent, String env, String kind, String detail, Instant firstSeen, Instant resolvedAt) {
            this.agent = agent;
            this.env = env;
            this.kind = kind;
            this.detail = detail;
            this.firstSeen = firstSeen;
            this.resolvedAt = resolvedAt;
        }

        public String kind() { return kind; }
        public String detail() { return detail; }
        public Instant resolvedAt() { return resolvedAt; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Row row && Objects.equals(agent, row.agent) && Objects.equals(kind, row.kind);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agent, kind);
        }
    }
}
