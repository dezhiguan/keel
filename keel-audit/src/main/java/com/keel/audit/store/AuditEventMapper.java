package com.keel.audit.store;

import com.keel.audit.chain.ChainStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Inserts audit rows. This class never updates or deletes audit_event.
 * The chain head lives in audit_chain_head and is the only row that moves.
 */
public class AuditEventMapper {
    public static String partitionName(Instant ts) {
        if (ts == null) {
            throw new IllegalArgumentException("ts 缺失");
        }
        return "audit_event_" + DateTimeFormatter.ofPattern("yyyy_MM").withZone(ZoneOffset.UTC).format(ts);
    }

    public void ensurePartition(Connection connection, Instant ts) throws SQLException {
        String name = partitionName(ts);
        var month = ts.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        String from = month.toString();
        String to = month.plusMonths(1).toString();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + name
                    + " PARTITION OF audit_event FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
        }
    }

    public void insertEvent(Connection connection, String eventId, String agent, Instant ts, String canonical, String prev, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_event (event_id, agent, ts, canonical_json, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, eventId);
            statement.setString(2, agent);
            statement.setTimestamp(3, java.sql.Timestamp.from(ts));
            statement.setString(4, canonical);
            statement.setString(5, prev);
            statement.setString(6, hash);
            statement.executeUpdate();
        }
    }

    public String lockHead(Connection connection, String agent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT hash FROM audit_chain_head WHERE agent = ? FOR UPDATE")) {
            statement.setString(1, agent);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** Serializes the first insert too. SELECT FOR UPDATE does not lock a missing head row. */
    public void advisoryLock(Connection connection, String agent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtext(?))")) {
            statement.setString(1, agent);
            statement.execute();
        }
    }

    public ChainStore.Head readHead(Connection connection, String agent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT hash, count FROM audit_chain_head WHERE agent = ? FOR UPDATE")) {
            statement.setString(1, agent);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new ChainStore.Head(rows.getString(1), rows.getLong(2));
            }
        }
    }

    public void upsertHead(Connection connection, String agent, String hash, long count) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_chain_head (agent, hash, count) VALUES (?, ?, ?)
                ON CONFLICT (agent) DO UPDATE SET hash = EXCLUDED.hash, count = EXCLUDED.count
                """)) {
            statement.setString(1, agent);
            statement.setString(2, hash);
            statement.setLong(3, count);
            statement.executeUpdate();
        }
    }

    public List<ChainStore.Link> listEvents(Connection connection, String agent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, agent, prev_hash, hash, canonical_json
                FROM audit_event WHERE agent = ?
                """)) {
            statement.setString(1, agent);
            try (ResultSet rows = statement.executeQuery()) {
                var links = new java.util.ArrayList<ChainStore.Link>();
                while (rows.next()) {
                    links.add(new ChainStore.Link(
                            rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5)));
                }
                return links;
            }
        }
    }

    public static Connection open(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
