package com.keel.llm;

import org.flywaydb.core.Flyway;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Postgres history for keel_llm.llm_spend. The caller supplies a database that already exists. */
public final class JdbcSpendLedger implements SpendLedger {
    private final javax.sql.DataSource dataSource;

    public JdbcSpendLedger(String url, String username, String password) {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(username == null ? "" : username);
        dataSource.setPassword(password == null ? "" : password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        this.dataSource = dataSource;
    }

    @Override
    public void append(Gateway.Spend row) {
        update("""
                INSERT INTO llm_spend(attempt_id, request_id, alias, model, status, input_tokens, output_tokens,
                    cache_tokens, cost_cny, reserved_cny, price_version, route_version, trace_id, latency_ms, timed_out, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (attempt_id) DO NOTHING
                """, statement -> {
            statement.setString(1, row.attemptId());
            statement.setString(2, row.requestId());
            statement.setString(3, row.alias());
            statement.setString(4, row.model());
            statement.setString(5, row.status());
            statement.setObject(6, row.inputTokens() < 0 ? null : row.inputTokens());
            statement.setObject(7, row.outputTokens() < 0 ? null : row.outputTokens());
            statement.setObject(8, row.cacheTokens() < 0 ? null : row.cacheTokens());
            statement.setBigDecimal(9, row.costCny());
            statement.setBigDecimal(10, row.reservedCny() == null ? BigDecimal.ZERO : row.reservedCny());
            statement.setString(11, row.priceVersion() == null ? "" : row.priceVersion());
            statement.setString(12, row.routeVersion() == null ? "" : row.routeVersion());
            statement.setString(13, row.traceId() == null ? "" : row.traceId());
            statement.setLong(14, row.latencyMs());
            statement.setBoolean(15, row.timedOut());
            statement.setTimestamp(16, Timestamp.from(row.ts()));
        });
    }

    /** Preserve recent Redis rows before switching reads to persistent history. */
    public void importRecent(List<Gateway.Spend> rows) {
        if (rows.isEmpty()) return;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO llm_spend(attempt_id,request_id,alias,model,status,input_tokens,output_tokens,
                  cache_tokens,cost_cny,reserved_cny,price_version,route_version,trace_id,latency_ms,timed_out,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(attempt_id) DO NOTHING
                """)) {
            for (var row : rows) {
                statement.setString(1,row.attemptId()); statement.setString(2,row.requestId());
                statement.setString(3,row.alias()); statement.setString(4,row.model()); statement.setString(5,row.status());
                statement.setObject(6,row.inputTokens()<0?null:row.inputTokens());
                statement.setObject(7,row.outputTokens()<0?null:row.outputTokens());
                statement.setObject(8,row.cacheTokens()<0?null:row.cacheTokens());
                statement.setBigDecimal(9,row.costCny()); statement.setBigDecimal(10,row.reservedCny());
                statement.setString(11,row.priceVersion()); statement.setString(12,row.routeVersion());
                statement.setString(13,row.traceId()); statement.setLong(14,row.latencyMs());
                statement.setBoolean(15,row.timedOut()); statement.setTimestamp(16,Timestamp.from(row.ts()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception error) { throw new IllegalStateException("Cannot preserve recent gateway bills",error); }
    }

    @Override
    public SpendCursor.Page page(String alias, String cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        var mark = SpendCursor.decode(cursor);
        var rows = query(alias, mark, size + 1);
        String next = null;
        if (rows.size() > size) {
            rows.removeLast();
            var last = rows.getLast();
            next = SpendCursor.encode(last.ts(), last.attemptId());
        }
        return new SpendCursor.Page(List.copyOf(rows), next);
    }

    @Override
    public List<Gateway.Spend> latestSettledByModel() {
        var sql = """
                SELECT DISTINCT ON (model) attempt_id, request_id, alias, model, status, input_tokens, output_tokens,
                    cache_tokens, cost_cny, reserved_cny, price_version, route_version, trace_id, latency_ms, timed_out, created_at
                FROM llm_spend WHERE status = 'SETTLED'
                ORDER BY model, created_at DESC, attempt_id DESC
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            return read(statement);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Gateway.Spend> query(String alias, SpendCursor.Mark mark, int limit) {
        var sql = """
                SELECT attempt_id, request_id, alias, model, status, input_tokens, output_tokens, cache_tokens,
                    cost_cny, reserved_cny, price_version, route_version, trace_id, latency_ms, timed_out, created_at
                FROM llm_spend_history
                WHERE (? = '' OR alias = ?)
                  AND (?::timestamptz IS NULL OR created_at < ? OR (created_at = ? AND attempt_id < ?))
                ORDER BY created_at DESC, attempt_id DESC
                LIMIT ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            var name = alias == null ? "" : alias;
            statement.setString(1, name);
            statement.setString(2, name);
            if (mark == null) {
                statement.setTimestamp(3, null);
                statement.setTimestamp(4, null);
                statement.setTimestamp(5, null);
                statement.setString(6, "");
            } else {
                var ts = Timestamp.from(mark.ts());
                statement.setTimestamp(3, ts);
                statement.setTimestamp(4, ts);
                statement.setTimestamp(5, ts);
                statement.setString(6, mark.attemptId());
            }
            statement.setInt(7, limit);
            return read(statement);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Gateway.Spend> read(java.sql.PreparedStatement statement) throws java.sql.SQLException {
        var rows = new ArrayList<Gateway.Spend>();
        try (var result = statement.executeQuery()) {
            while (result.next()) {
                var cost = result.getBigDecimal("cost_cny");
                if (result.wasNull()) {
                    cost = null;
                }
                var input = (Integer) result.getObject("input_tokens");
                var output = (Integer) result.getObject("output_tokens");
                var cache = (Integer) result.getObject("cache_tokens");
                rows.add(new Gateway.Spend(
                        result.getString("alias"),
                        result.getString("model"),
                        input == null ? -1 : input,
                        output == null ? -1 : output,
                        cost,
                        result.getString("request_id"),
                        result.getTimestamp("created_at").toInstant(),
                        result.getLong("latency_ms"),
                        result.getBoolean("timed_out"),
                        result.getString("status"),
                        result.getBigDecimal("reserved_cny"),
                        result.getString("attempt_id"),
                        result.getString("trace_id"),
                        result.getString("price_version"),
                        result.getString("route_version"),
                        cache == null ? -1 : cache));
            }
        }
        return rows;
    }

    private void update(String sql, Binder binder) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Binder {
        void bind(java.sql.PreparedStatement statement) throws java.sql.SQLException;
    }
}
