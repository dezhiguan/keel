package com.keel.audit.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.audit.chain.ChainStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One transaction per append: advisory lock, insert, then move the chain head. */
public class PostgresChainStore implements ChainStore {
    private final DataSource dataSource;
    private final AuditEventMapper mapper = new AuditEventMapper();
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadLocal<Connection> tx = new ThreadLocal<>();
    private final ThreadLocal<Boolean> failed = new ThreadLocal<>();

    public PostgresChainStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void lock(String agent) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            mapper.advisoryLock(connection, agent);
            tx.set(connection);
            failed.set(false);
        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    e.addSuppressed(ignored);
                }
            }
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Head head(String agent) {
        try {
            return mapper.readHead(connection(), agent);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void insert(Link link) {
        try {
            Instant ts = eventTime(link.canonicalJson());
            mapper.ensurePartition(connection(), ts);
            mapper.insertEvent(connection(), link.eventId(), link.agent(), ts,
                    link.canonicalJson(), link.prevHash() == null ? "" : link.prevHash(), link.hash());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void advance(String agent, String hash, long count) {
        try {
            mapper.upsertHead(connection(), agent, hash, count);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void rollback(String agent) {
        Connection connection = tx.get();
        failed.set(true);
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void unlock(String agent) {
        Connection connection = tx.get();
        boolean abort = Boolean.TRUE.equals(failed.get());
        tx.remove();
        failed.remove();
        if (connection == null) {
            return;
        }
        try {
            if (!abort) {
                connection.commit();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public List<Link> list(String agent) {
        try (Connection connection = dataSource.getConnection()) {
            return order(mapper.listEvents(connection, agent));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection connection() {
        Connection connection = tx.get();
        if (connection == null) {
            throw new IllegalStateException("链头锁还没拿到");
        }
        return connection;
    }

    private Instant eventTime(String canonical) {
        try {
            var node = json.readTree(canonical).path("ts");
            if (node.isTextual()) {
                return Instant.parse(node.asText());
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("canonical_json", e);
        }
        return Instant.now();
    }

    static List<Link> order(List<Link> rows) {
        Map<String, Link> byPrev = new LinkedHashMap<>();
        for (Link link : rows) {
            byPrev.putIfAbsent(link.prevHash() == null ? "" : link.prevHash(), link);
        }
        var ordered = new ArrayList<Link>();
        Link next = byPrev.get("");
        while (next != null && ordered.size() < rows.size()) {
            ordered.add(next);
            next = byPrev.get(next.hash());
        }
        for (Link link : rows) {
            if (!ordered.contains(link)) {
                ordered.add(link);
            }
        }
        return ordered;
    }
}
