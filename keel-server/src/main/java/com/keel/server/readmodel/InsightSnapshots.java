package com.keel.server.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Shared persistence and fenced leases; no external IO while a database lock is held. */
@Component
public class InsightSnapshots {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public InsightSnapshots(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public record Snapshot(String payload, Instant at, boolean failed) {}
    public Snapshot read(String key) {
        return jdbc.query("SELECT payload::text, updated_at, failed FROM insight_snapshot WHERE snapshot_key = ?",
                (rs,n) -> new Snapshot(rs.getString(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(), rs.getBoolean(3)), key)
                .stream().findFirst().orElse(new Snapshot(null, null, false));
    }
    public String claim(String key, Duration lease) {
        jdbc.update("INSERT INTO insight_snapshot(snapshot_key) VALUES (?) ON CONFLICT DO NOTHING", key);
        var owner = UUID.randomUUID().toString();
        return jdbc.update("""
                UPDATE insight_snapshot SET lease_owner=?, lease_until=now() + (? * interval '1 millisecond')
                WHERE snapshot_key=? AND lease_until <= now() AND retry_at <= now()
                """, owner, lease.toMillis(), key) == 1 ? owner : null;
    }
    public boolean finish(String key, String owner, Object value, Duration delay) {
        return jdbc.update("""
                UPDATE insight_snapshot SET payload=?::jsonb, updated_at=now(), failed=false,
                  lease_until=now(), retry_at=now() + (? * interval '1 millisecond'), lease_owner=null
                WHERE snapshot_key=? AND lease_owner=? AND lease_until > now()
                """, encode(value), delay.toMillis(), key, owner) == 1;
    }
    public void fail(String key, String owner, Duration delay) {
        jdbc.update("""
                UPDATE insight_snapshot SET failed=true, lease_until=now(),
                  retry_at=now() + (? * interval '1 millisecond'), lease_owner=null
                WHERE snapshot_key=? AND lease_owner=?
                """, delay.toMillis(), key, owner);
    }
    /** Transactional index writes lock this row and reject expired owners before touching observations. */
    public boolean lockOwner(String key, String owner) {
        return !jdbc.queryForList("SELECT snapshot_key FROM insight_snapshot WHERE snapshot_key=? AND lease_owner=? AND lease_until>now() FOR UPDATE",
                String.class, key, owner).isEmpty();
    }
    public <T> T decode(String text, Class<T> type) {
        try { return json.readValue(text, type); } catch (Exception e) { throw new IllegalStateException("Invalid insight snapshot", e); }
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Invalid insight snapshot", e); }
    }
    public static Map<String,Object> status(Snapshot snap, boolean complete, Instant from, Instant to) {
        var out = new java.util.LinkedHashMap<String,Object>();
        out.put("state", snap.failed() ? (snap.at() == null ? "unavailable" : "stale")
                : snap.at() == null ? "loading" : complete ? "ready" : "loading");
        out.put("complete", complete); out.put("updatedAt", snap.at()); out.put("from", from); out.put("to", to);
        return out;
    }
}
